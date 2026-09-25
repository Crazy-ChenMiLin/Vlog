#Requires -Version 5.1
<#
    知光 Shared Agent — 自动化验收入口 (M4 + M5)  v2（唯一验收入口）
    锚点: D:\resume-project\scripts\acceptance\run_all.ps1

    强制范围(验收文档 §16): Gate 0 / A / B / C 必须 PASS；F+ 允许 SKIP(不得显示 PASS)。

    用法:
      powershell -ExecutionPolicy Bypass -File scripts/acceptance/run_all.ps1
    可选参数:
      -JavaUrl   http://localhost:8080   -PortalUrl  http://localhost:4100
      -Repeat     Gate A nonce 重复次数 (默认 3；§17 一次结果不算证据)

    关键设计:
      - New-NoncePost() 每次用唯一 nonce 自建草稿帖, 不污染共享数据, 收尾可清理(验收 §18)
      - A7 断言 Agent 回复含本次唯一 nonce（只存在于正文）=> 证明 get_post 真实读正文
      - Session 由本地 Pithagoras SQLite(portal.db sessions 表) 查询, 不依赖 portal login
      - A9 幂等: 对同一 trigger 的 count=1 且 dupcheck 被 UNIQUE 拒绝
      - Gate B: 两帖隔离(不同 session.id + 正文不串), Gate C: 同帖复用(同 session.id)
    退出码: 0=全必选 PASS  1=断言失败  2=环境/依赖  3=脚本异常
#>
param(
    [string]$JavaUrl   = "http://localhost:8080",
    [string]$PortalUrl = "http://localhost:4100",
    [int]    $Repeat   = 3
)

$ErrorActionPreference = "Stop"
$JDK    = "C:\Users\26487\.jdks\ms-21.0.9\bin"
$JDBC   = "C:\Users\26487\.m2\repository\com\mysql\mysql-connector-j\9.7.0\mysql-connector-j-9.7.0.jar"
$Tools  = Join-Path $PSScriptRoot "tools"
$Pith   = "D:\code_project\pi-session\pithagoras"
$DB     = Join-Path $Pith "data\portal.db"
$PY     = "C:\Users\26487\.workbuddy\binaries\python\versions\3.13.12\python.exe"
$java   = "$JDK\java.exe"
$javac  = "$JDK\javac.exe"
$Stamp  = Get-Date -Format "yyyy-MM-dd_HHmmss"
$Out    = Join-Path "D:\resume-project\artifacts\acceptance" $Stamp
New-Item -ItemType Directory -Path (Split-Path $Out) -Force | Out-Null
New-Item -ItemType Directory -Path $Out -Force | Out-Null

$script:created = @{}          # 每次 run 新建的草稿帖 (用于 cleanup)
$script:results = [ordered]@{ run=$Stamp; gates=@(); failures=@() }

function Fail([string]$g,[string]$m){ $script:results.failures += "$g : $m"; Write-Host "  [FAIL] $m" -ForegroundColor Red }
function Pass([string]$m){ Write-Host "  [PASS] $m" -ForegroundColor Green }
function Info([string]$m){ Write-Host "  [INFO] $m" -ForegroundColor Gray }

function Get-Jwt([string]$uid="1"){
    return (& $java -cp "$($Tools)" JwtTool $uid).Trim()
}

# 用 AcceptanceDb.mkpost 建含唯一 nonce 的草稿帖, 返回 @{post;nonce;title;body}
function New-NoncePost([string]$tag,[string]$uid="1"){
    $nonce = "ZG-NONCE-$tag-$(Get-Random -Minimum 100000 -Maximum 999999)"
    $title = "ACCEPTANCE_POST_$($tag)_$($nonce.Substring(11))"
    $body  = "ACCEPTANCE_NONCE_$nonce`n本次验收正文,唯一标记: $nonce"
    $jwt = Get-Jwt $uid
    $mko = (& $java -cp "$($Tools);$($JDBC)" AcceptanceDb mkpost $title $body $jwt $JavaUrl 2>&1 | Out-String)
    $id = (($mko -split "`n" | Where-Object { $_ -match "^POSTID=" }) | Select-Object -First 1) -replace "^POSTID=",""
    $id = $id.Trim()
    if (-not $id) { throw "mkpost 失败: $mko" }
    $script:created[$id] = $true
    return @{ post=$id; nonce=$nonce; title=$title; body=$body }
}

# 向帖子发 @知光 评论, 内容指示 Agent 读正文并回报唯一标记
function Send-Trigger([string]$post,[string]$uid="1"){
    $jwt = Get-Jwt $uid
    $q = "@知光 请使用 get_post 工具读取本帖正文，然后把正文里那个以 ZG-NONCE- 开头的唯一标记原样回报给我。"
    $body = "{`"postId`":`"$post`",`"content`":`"$q`"}"
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
    Invoke-RestMethod -Uri "$JavaUrl/api/v1/comments" -Method Post `
        -Headers @{ "Authorization" = "Bearer $jwt" } -Body $bytes `
        -ContentType "application/json; charset=utf-8" -TimeoutSec 30 | Out-Null
}

# 轮询 Java comments API 直到出现 AGENT 评论; 返回第一条 AGENT 评论对象或 $null
function Wait-AgentReply([string]$post,[int]$timeoutSec=150){
    $jwt = Get-Jwt "1"
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $r = Invoke-RestMethod -Uri "$JavaUrl/api/v1/comments?postId=$post&size=8" `
                -Headers @{ "Authorization" = "Bearer $jwt" } -TimeoutSec 20
            $agent = $r | Where-Object { $_.authorType -eq "AGENT" } | Select-Object -First 1
            if ($agent) { return $agent }
        } catch { }
        Start-Sleep -Seconds 4
    }
    return $null
}

# 从本地 SQLite 查询 channel_key 的 session 数组 @{id;key}[]
function Get-SessionFor([string]$channelKey){
    # 用预置的 tools/sessq.py(Write 工具生成, 稳定) 精确查询 sessions 表
    # argv 传 key, 永不空输出/不依赖 here-string 生成 py 文件(规避 PowerShell 捕获异常)
    $sessq = Join-Path $Tools "sessq.py"
    if (-not (Test-Path $sessq)) { return @() }
    $qc = $channelKey.ToString()
    if (-not ($qc -like "zhiguang*")) { $qc = "zhiguang:post:$qc" }
    for ($try=0; $try -lt 3; $try++) {
        $outJson = (($(& $PY $sessq $qc 2>&1) ) -join "").Trim()
        if ($outJson -and $outJson -ne "null" -and $outJson -ne "[]") {
            try { return @($outJson | ConvertFrom-Json) } catch { }
        } elseif ($outJson -eq "[]") {
            # 明确无结果, 无需重试(除非是刚触发可能还在 WAL, 快速重试一次)
            if ($try -eq 0) { Start-Sleep -Milliseconds 1200; continue }
            return @()
        }
        Start-Sleep -Milliseconds 600
    }
    return @()
}

# 查询 people 表的 person role(用于 C1 断言 M7 映射为 colleague)
function Get-PersonRole([string]$personKey){
    $bagq = Join-Path $Tools "bagq.py"
    if (-not (Test-Path $bagq)) { return $null }
    $outJson = (($(& $PY $bagq $personKey 2>&1) ) -join "").Trim()
    if (-not $outJson -or $outJson -eq "null" -or $outJson -eq "[]") { return $null }
    try { $rows = @($outJson | ConvertFrom-Json); if ($rows.Count -eq 0) { return $null }; return $rows[0].role } catch { return $null }
}

# 帖子 agent_run 证据文件 -> 对象
function Get-Evidence([string]$Id,[string]$name){
    $f = Join-Path $Out $name
    & $java -cp "$($Tools);$($JDBC)" AcceptanceDb evidence $Id $f 2>&1 | Out-Null
    if (-not (Test-Path $f)) { return $null }
    return (Get-Content $f -Raw -Encoding UTF8 | ConvertFrom-Json)
}

try {
    # ---------------- Gate 0: Preflight ----------------
    Write-Host "`n=== Gate 0: Preflight ===" -ForegroundColor Cyan
    $g0 = [ordered]@{ id="Gate-0"; status="PASS"; assertions=0; failed=0 }
    foreach ($p in @(8080,4100,4180)) {
        $g0.assertions++
        if (Get-NetTCPConnection -LocalPort $p -State Listen -EA SilentlyContinue) { Pass "port $p listening" }
        else { $g0.failed++; Fail "Gate-0" "port $p not listening" }
    }
    $g0.assertions++
    if (Test-Path $DB) { Pass "pithagoras sqlite 可读 ($((Get-Item $DB).Length) B)" } else { $g0.failed++; Fail "Gate-0" "portal.db missing at $DB" }

    & $javac -encoding UTF-8 -cp $JDBC (Join-Path $Tools "AcceptanceDb.java") -d $Tools 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { $g0.failed++; Fail "Gate-0" "AcceptanceDb 编译失败" }
    & $javac -encoding UTF-8 (Join-Path $Tools "JwtTool.java") -d $Tools 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { $g0.failed++; Fail "Gate-0" "JwtTool 编译失败" } else { Pass "编译 AcceptanceDb + Jwt 工具" }

    $secFile = "D:\code_project\pi-session\.workbuddy\secrets\webhook.secret"
    $g0.assertions++
    if (Test-Path $secFile){
        $sec=(Get-Content $secFile -Raw).Trim()
        if ($sec -match "zhiguang-dev-secret-2026|^password$|^123456$") { $g0.failed++; Fail "Gate-0" "弱 secret" }
        else { Pass "Webhook secret 强随机 len $($sec.Length)" }
    } else { $g0.failed++; Fail "Gate-0" "secret 文件缺失" }

    if ($g0.failed -gt 0){ $g0.status="FAIL"; $script:results.gates += $g0; Write-Host "OVERALL: FAIL (Gate 0)"; exit 2 }
    $results.gates += $g0

    # ---------------- Gate A: MVP E2E + unique nonce (重复 $Repeat 次) ----------------
    Write-Host "`n=== Gate A: MVP E2E + unique nonce (repeat x$Repeat) ===" -ForegroundColor Cyan
    $gA = [ordered]@{ id="Gate-A"; status="PASS"; assertions=0; failed=0; runs=@() }
    $aPosts = @()   # 记录 Gate A 建的帖, 供 C/B 复用
    for ($i=1; $i -le $Repeat; $i++) {
        Info "--- Gate A run $i/$Repeat ---"
        $np = New-NoncePost "A$i" "1"
        $aPosts += $np
        Info "post=$($np.post) nonce=$($np.nonce)"
        Send-Trigger $np.post "1"
        $agent = Wait-AgentReply $np.post
        if (-not $agent) {
            $gA.failed++; $gA.runs += @{i=$i; a7=$false}; Fail "Gate-A" "run $i : 无 Agent 回复(超时) post=$($np.post)"; continue
        }
        $contains = ($agent.content -like "*$($np.nonce)*")
        $gA.assertions++
        if ($contains) { Pass "run $i : A7 回复含 nonce ($($np.nonce))" }
        else { $gA.failed++; Fail "Gate-A" "run $i : A7 nonce 缺失; 回复: $($agent.content)" }
        $gA.runs += @{ i=$i; post=$np.post; nonce=$np.nonce; a7=$contains }
    }

    # A9 idempotency (用第一个 Gate A 帖)
    Write-Host "`n--- A9 idempotency ---" -ForegroundColor Cyan
    if ($aPosts.Count -eq 0) {
        $gA.failed++; Fail "Gate-A" "A9: 无 Gate A 帖可断言"
    } else {
        $p0 = $aPosts[0].post
        $ev = Get-Evidence $p0 "db_A1.json"
        $gA.assertions++
        if ($ev -and $ev.agentRunLatest) {
            $trig = $ev.agentRunLatest.triggerCommentId
            $cnt = (& $java -cp "$($Tools);$($JDBC)" AcceptanceDb count $trig 2>&1 | Out-String).Trim()
            if ($cnt -eq "1") { Pass "A9: trigger $trig 只产生 1 条 agent_run ($cnt)" }
            else { $gA.failed++; Fail "Gate-A" "A9: trigger $trig 产生 $cnt 条 run" }
            $dup = (& $java -cp "$($Tools);$($JDBC)" AcceptanceDb dupcheck $trig 2>&1 | Out-String)
            $gA.assertions++
            if ($dup -match "REJECTED") { Pass "A9: 重复 trigger 被 UNIQUE 拒绝" }
            else { $gA.failed++; Fail "Gate-A" "A9: 重复 trigger 未被拒绝: $dup" }
        } else { $gA.failed++; Fail "Gate-A" "A9: 无 evidence/trigger" }
    }
    $gA.status = if ($gA.failed -gt 0) { "FAIL" } else { "PASS" }
    $results.gates += $gA

    # ---------------- Gate C: 同帖复用 (post 复用 -> 同一 session) ----------------
    Write-Host "`n=== Gate C: 同帖复用 (same post -> same session) ===" -ForegroundColor Cyan
    $gC = [ordered]@{ id="Gate-C"; status="PASS"; assertions=0; failed=0 }
    $pC = $aPosts[0].post
    $s1 = Get-SessionFor "zhiguang:post:$pC"
    # 同一帖再触发一次
    Send-Trigger $pC "1"
    Start-Sleep -Seconds 8   # 让 run 落库; 只是验证 session 复用
    $s2 = Get-SessionFor "zhiguang:post:$pC"
    $gC.assertions++
    if ($s1.Count -ge 1) { Pass "Gate C: post:$pC 有 session ($($s1[0].id))" } else { $gC.failed++; Fail "Gate-C" "第一次触发未找到 session" }
    if ($s2.Count -ge 1) { Pass "Gate C: 再次触发仍进同一 session key" } else { $gC.failed++; Fail "Gate-C" "二次触发未找到 session" }
    if ($s1.Count -ge 1 -and $s2.Count -ge 1) {
        # 同一 channel_key 的 session 应只有 1 条(redis 不重复建)
        $gC.assertions++
        if ($s2.Count -eq 1) { Pass "Gate C: 同帖复用 => 仅 1 个 session" } else { $gC.failed++; Fail "Gate-C" "同帖出现 $($s2.Count) 个 session" }
    }
    $gC.status = if ($gC.failed -gt 0) { "FAIL" } else { "PASS" }
    $results.gates += $gC

    # ---------------- Gate B: 跨帖隔离 (用 Gate A 两个不同帖) ----------------
    Write-Host "`n=== Gate B: 跨帖隔离 (different post -> different session + 正文不串) ===" -ForegroundColor Cyan
    $gB = [ordered]@{ id="Gate-B"; status="PASS"; assertions=0; failed=0 }
    if ($aPosts.Count -lt 2) {
        # 只跑了一次 A -> 再造第二个帖做隔离
        $np2 = New-NoncePost "B" "1"; Send-Trigger $np2.post "1"; $ag2 = Wait-AgentReply $np2.post
        $pair = @($aPosts[0]; $np2)
    } else {
        $pair = @($aPosts[0]; $aPosts[1])
    }
    $kA = "zhiguang:post:$($pair[0].post)"; $kB = "zhiguang:post:$($pair[1].post)"
    $sA = Get-SessionFor $kA; $sB = Get-SessionFor $kB
    $gB.assertions++
    if ($sA.Count -ge 1 -and $sB.Count -ge 1) { Pass "两个帖各自有 session" } else { $gB.failed++; Fail "Gate-B" "session 缺失 (A=$($sA.Count) B=$($sB.Count))" }
    if ($sA.Count -ge 1 -and $sB.Count -ge 1) {
        $gB.assertions++
        if ($sA[0].id -ne $sB[0].id) { Pass "不同帖 => 不同 session.id ($($sA[0].id) vs $($sB[0].id))" }
        else { $gB.failed++; Fail "Gate-B" "两帖 session.id 相同" }
    }
    # 正文隔离: 各自 Agent 回复含自己的 nonce 且不含对方的
    $jwt = Get-Jwt "1"
    foreach ($x in @(@{np=$pair[0]; other=$pair[1]}, @{np=$pair[1]; other=$pair[0]})) {
        $r = Invoke-RestMethod -Uri "$JavaUrl/api/v1/comments?postId=$($x.np.post)&size=8" -Headers @{ Authorization = "Bearer $jwt" } -TimeoutSec 20
        $ag = $r | Where-Object { $_.authorType -eq "AGENT" } | Select-Object -First 1
        $hasOwn = $ag -and ($ag.content -like "*$($x.np.nonce)*")
        $hasOther = $ag -and ($ag.content -like "*$($x.other.nonce)*")
        $gB.assertions++
        if ($hasOwn -and -not $hasOther) { Pass "帖 $($x.np.post): 含本帖 nonce、不含异帖" }
        else { $gB.failed++; Fail "Gate-B" "帖 $($x.np.post): hasOwn=$hasOwn hasOther=$hasOther" }
    }
    $gB.status = if ($gB.failed -gt 0) { "FAIL" } else { "PASS" }
    $results.gates += $gB

    # ---------------- Gate C1: M7 Trusted External Actor (webhook 动态 from -> colleague, 不再被陌生人闸拦) ----------------
    Write-Host "`n=== Gate C1: M7 动态 from.id 映射 colleague (同一 post, A/B 两真实登录用户) ===" -ForegroundColor Cyan
    $gM7 = [ordered]@{ id="Gate-C1"; status="PASS"; assertions=0; failed=0 }
    # webhook secret: 从 Gate0 校验过的文件重读一次, 保证可用
    $m7key  = "post:C1-$(Get-Random -Minimum 100000 -Maximum 999999)"     # 临时 webhook 会话 key(不建 Java 帖)
    $idA    = "1188880$(Get-Random -Minimum 1000 -Maximum 9999)"          # A = 动态登录用户1
    $idB    = "2188880$(Get-Random -Minimum 1000 -Maximum 9999)"          # B = 动态登录用户2 (唯一, 避免撞)
    $m7Secret = ""
    if (Test-Path $secFile) { $m7Secret = (Get-Content $secFile -Raw).Trim() }
    if (-not $m7Secret) { $gM7.failed++; Fail "Gate-C1" "webhook secret 缺失, 无法做 webhook 断言"; $gM7.status="FAIL"; $results.gates += $gM7 }
    else {
        # 先触 A
        $bodyA = @{ message="回复我一个字: ALPHA"; session=$m7key; from=@{ id=$idA; name="UserC1A" } } | ConvertTo-Json -Compress
        $repA = ""; $httpA = ""
        try {
            $resp = Invoke-RestMethod -Uri "http://localhost:4180/" -Method Post `
                -Headers @{ "Content-Type"="application/json"; "X-Portal-Secret"=$m7Secret } `
                -Body $bodyA -TimeoutSec 60
            $repA = if ($resp.reply) { $resp.reply } else { "$resp" }
            $tryA = "HTTP-OK"
        } catch { $tryA = "WEBHOOK-EX $($_.Exception.Message)" }
        $gM7.assertions++
        $rejectA = ($repA -match "I only talk" -or $repA -match "have not been introduced" -or $repA -match "havent been introduced" -or $repA -match "haven't been introduced" -or $repA -match "stranger")
        if ($tryA -ne "HTTP-OK") { $gM7.failed++; Fail "Gate-C1" "A($idA) webhook 调用异常: $tryA" }
        elseif ($rejectA) { $gM7.failed++; Fail "Gate-C1" "A($idA) 仍被陌生人闸拦: $repA" }
        else { Pass "A($idA) 未被陌生人闸拦 (reply: $($repA.Trim()))" }

        # 再触 B (同一 post 会话, 换真实发言者)
        $bodyB = @{ message="从我这边回复: OMEGA"; session=$m7key; from=@{ id=$idB; name="UserC1B" } } | ConvertTo-Json -Compress
        $repB = ""; $tryB = ""
        try {
            $resp = Invoke-RestMethod -Uri "http://localhost:4180/" -Method Post `
                -Headers @{ "Content-Type"="application/json"; "X-Portal-Secret"=$m7Secret } `
                -Body $bodyB -TimeoutSec 60
            $repB = if ($resp.reply) { $resp.reply } else { "$resp" }
            $tryB = "HTTP-OK"
        } catch { $tryB = "WEBHOOK-EX $($_.Exception.Message)" }
        $gM7.assertions++
        $rejectB = ($repB -match "I only talk" -or $repB -match "have not been introduced" -or $repB -match "stranger")
        if ($tryB -ne "HTTP-OK") { $gM7.failed++; Fail "Gate-C1" "B($idB) webhook 调用异常: $tryB" }
        elseif ($rejectB) { $gM7.failed++; Fail "Gate-C1" "B($idB) 仍被陌生人闸拦: $repB" }
        else { Pass "B($idB) 未被陌生人闸拦(reply: $($repB.Trim()))" }

        # §10.4 last_person_key 随真实发言者变: 该 post 会话 last_person_key 应为 B
        Start-Sleep -Seconds 2
        $m7Sessions = Get-SessionFor "zhiguang:$m7key"
        $gM7.assertions++
        if ($m7Sessions.Count -ge 1 -and $m7Sessions[0].last_person_key) {
            $lpk = $m7Sessions[0].last_person_key
            $wantB = "zhiguang:$idB"
            if ($lpk -eq $wantB) { Pass "last_person_key=$lpk = B($idB) 末人" }
            else { $gM7.failed++; Fail "Gate-C1" "last_person_key=$lpk, 期望 $wantB(不是最后发言者)" }
        } else { $gM7.failed++; Fail "Gate-C1" "C1 会话未落库 / 无 last_person_key(rooms=$($m7Sessions.Count))" }

        # people role: A/B 均应为 colleague(非 unknown)
        $gM7.assertions++
        $roleA = Get-PersonRole "zhiguang:$idA"; $roleB = Get-PersonRole "zhiguang:$idB"
        if (($roleA -eq "colleague") -and ($roleB -eq "colleague")) { Pass "people role: A=$roleA B=$roleB (均 colleague)" }
        else { $gM7.failed++; Fail "Gate-C1" "people role A=$roleA B=$roleB, 期望两者 colleague" }

        # C1 会话已用完, 记录待清理 (将人保留到 cleanup 统一闭)
        $script:m7Persons = @("zhiguang:$idA","zhiguang:$idB")
        $script:m7SessionKey = "zhiguang:$m7key"
    }
    $gM7.status = if ($gM7.failed -gt 0) { "FAIL" } else { "PASS" }
    $results.gates += $gM7

    # ---------------- summary ----------------
    $overall = "PASS"
    foreach ($g in $results.gates) { if ($g.status -eq "FAIL") { $overall = "FAIL" } }
    if ($results.gates | Where-Object { $_.status -eq "SKIP" }) { $overall = "FAIL" }
    $results.overall = $overall
    $script:ovrExit = if ($overall -eq "PASS") { 0 } else { 1 }   # 供 finally 末尾取退出码

    $sum = @("# Acceptance Summary — $Stamp","","Overall: **$overall**","")
    foreach ($g in $results.gates) { $sum += "$($g.id): $($g.status)  (assertions $($g.assertions), failed $($g.failed))" }
    $sum += ""; $sum += "## Failures"
    if ($results.failures.Count -eq 0) { $sum += "- none" } else { $results.failures | ForEach-Object { $sum += "- $_" } }
    $sum -join "`n" | Set-Content (Join-Path $Out "summary.md") -Encoding UTF8
    $resultFile = Join-Path $Out "results.json"
    @{ run=$Stamp; overall=$overall; gates=$results.gates; failures=$results.failures } | ConvertTo-Json -Depth 8 | Set-Content $resultFile -Encoding UTF8

    Write-Host "`n=== RESULT ===" -ForegroundColor Cyan
    foreach ($g in $results.gates) { Write-Host ("{0}: {1}" -f $g.id, $g.status) }
    Write-Host "OVERALL: $overall"
    Write-Host "Evidence: $Out"

} catch {
    Write-Host "[ERROR] acceptance 异常: $($_.Exception.Message)`n$($_.ScriptStackTrace)" -ForegroundColor Red
    $script:ovrExit = 3      # 统一由 finally 退出; 保证 cleanup 先执行
}
finally {
    Write-Host "`n--- cleanup ---" -ForegroundColor Yellow
    try {
        $cleaned = @()
        foreach ($p in $script.created.Keys) {
            & $java -cp "$($Tools);$($JDBC)" AcceptanceDb cleanup $p 2>&1 | Out-File (Join-Path $Out "cleanup.txt") -Append -Encoding UTF8 -EA SilentlyContinue
            $cleaned += $p
        }
        Write-Host "cleanup created posts: $($cleaned -join ', ')  (see cleanup.txt)"
    } catch { Write-Host "cleanup 失败: $($_.Exception.Message)" -ForegroundColor Red }
    # C1(M7) 临时 webhook 会话 + person 清理: 避免污染 portal.db(不同于 Java 帖, 用 c1clean.py 直接删)
    if ($script:m7SessionKey) {
        try {
            $c1clean = Join-Path $Tools "c1clean.py"
            $m7args = @($script:m7SessionKey)
            if ($script:m7Persons) { $m7args += $script:m7Persons }
            $cr = (& $PY $c1clean @m7args 2>&1) -join " "
            Write-Host "cleanup C1 session/persons: $cr"
        } catch { Write-Host "C1 cleanup 失败: $($_.Exception.Message)" -ForegroundColor Red }
    }
    # 退出码: 0=全必选 PASS, 1=断言失败; catch 分支的 exit 3 已直接返回。
    if ($null -eq $script:ovrExit) { $script:ovrExit = 1 }
    exit $script:ovrExit
}

if ($script.results.overall -eq "PASS") { exit 0 } else { exit 1 }