#Requires -Version 5.1
<#
   知光 Shared Agent — 自动化验收入口 (M4 + M5)

   用法:
     powershell -ExecutionPolicy Bypass -File scripts/acceptance/run_all.ps1

   可选参数:
     -PostA <id>      主测试帖子 (默认 350092465539256320)
     -PostB <id>      Gate B 用的第二个帖子 (默认取 feed 第一条的其它帖子)
     -RepeatA <n>     Gate A 重复次数 (默认 3, 验收 §17: 一次结果不算证据)
     -JavaUrl         默认 http://localhost:8080
     -PortalUrl       默认 http://localhost:4100

   退出码:
     0 = 全部必选 Gate PASS
     1 = 存在功能断言失败
     2 = 环境 / 依赖 / 服务启动失败
     3 = 验收脚本自身异常
#>
param(
    [string]$PostA   = "350092465539256320",
    [string]$PostB   = "",
    [int]   $RepeatA = 3,
    [string]$JavaUrl = "http://localhost:8080",
    [string]$PortalUrl = "http://localhost:4100"
)

$ErrorActionPreference = "Stop"
$JDK   = "C:\Users\26487\.jdks\ms-21.0.9\bin"
$JDBC  = "C:\Users\26487\.m2\repository\com\mysql\mysql-connector-j\9.7.0\mysql-connector-j-9.7.0.jar"
$Tools = Join-Path $PSScriptRoot "tools"
$Repo  = Split-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) -Parent
$Repo  = "D:\resume-project"
$Stamp = Get-Date -Format "yyyy-MM-dd_HHmmss"
$Out   = Join-Path $Repo "artifacts\acceptance\$Stamp"
$Pith  = "D:\code_project\pi-session\pithagoras"

New-Item -ItemType Directory -Path $Out -Force | Out-Null
$results = [ordered]@{ run = $Stamp; gates = @(); failures = @() }

function Fail([string]$gate, [string]$msg) {
    $script:results.failures += "$gate : $msg"
    Write-Host "  [FAIL] $msg" -ForegroundColor Red
}
function Pass([string]$msg) { Write-Host "  [PASS] $msg" -ForegroundColor Green }
function Info([string]$msg) { Write-Host "  [INFO] $msg" -ForegroundColor Gray }

try {
    # ---------------------------------------------------------------- Gate 0
    Write-Host "`n=== Gate 0: Preflight ===" -ForegroundColor Cyan
    $gate0 = [ordered]@{ id = "Gate-0"; status = "PASS"; assertions = 0; failed = 0 }
    foreach ($p in @(8080, 4100, 4180)) {
        $gate0.assertions++
        if (Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue) { Pass "port $p listening" }
        else { $gate0.failed++; Fail "Gate-0" "port $p not listening" }
    }
    $gate0.assertions++
    if ((Test-NetConnection -ComputerName "100.83.242.114" -Port 3306 -WarningAction SilentlyContinue).TcpTestSucceeded) { Pass "MySQL reachable" }
    else { $gate0.failed++; Fail "Gate-0" "MySQL unreachable" }

    # compile tools
    & "$JDK\javac.exe" -encoding UTF-8 -cp $JDBC (Join-Path $Tools "AcceptanceDb.java") -d $Tools 2>&1 | Out-Null
    & "$JDK\javac.exe" -encoding UTF-8 (Join-Path $Tools "JwtTool.java") -d $Tools 2>&1 | Out-Null

    # single zhiguang channel + strong secret (read Pithagoras SQLite)
    $secFile = "D:\code_project\pi-session\.workbuddy\secrets\webhook.secret"
    $gate0.assertions++
    if (Test-Path $secFile) {
        $sec = (Get-Content $secFile -Raw).Trim()
        if ($sec -match "zhiguang-dev-secret-2026|^password$|^123456$") { $gate0.failed++; Fail "Gate-0" "weak secret in use" }
        else { Pass "secret is strong random (len $($sec.Length))" }
    } else { $gate0.failed++; Fail "Gate-0" "secret file missing" }

    if ($gate0.failed -gt 0) { $gate0.status = "FAIL"; Write-Host "OVERALL: FAIL (Gate 0)" -ForegroundColor Red; exit 2 }
    $results.gates += $gate0

    # ---------------------------------------------------------------- Gate A
    Write-Host "`n=== Gate A: MVP end-to-end (repeat x$RepeatA) ===" -ForegroundColor Cyan
    $gateA = [ordered]@{ id = "Gate-A"; status = "PASS"; assertions = 0; failed = 0; runs = @() }
    $jwt = (& "$JDK\java.exe" -cp $Tools JwtTool 1).Trim()
    $jwt | Set-Content (Join-Path $Out "token.txt") -Encoding UTF8

    for ($i = 1; $i -le $RepeatA; $i++) {
        Info "--- Gate A run $i/$RepeatA ---"
        $json = "{`"postId`":`"$PostA`",`"content`":`"@知光 请使用 get_post 读取本帖，并把正文原文一字不差地回复给我。`"}"
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
        try {
            Invoke-RestMethod -Uri "$JavaUrl/api/v1/comments" -Method Post `
                -Headers @{ "Authorization" = "Bearer $jwt" } -Body $bytes `
                -ContentType "application/json; charset=utf-8" -TimeoutSec 30 | Out-Null
        } catch { $gateA.failed++; Fail "Gate-A" "run $i : comment POST failed: $($_.Exception.Message)"; continue }

        Start-Sleep -Seconds 60
        $dbFile = Join-Path $Out "db_A$i.json"
        & "$JDK\java.exe" -cp "$Tools;$JDBC" AcceptanceDb evidence $PostA $dbFile 2>&1 | Out-Null
        if (-not (Test-Path $dbFile)) { $gateA.failed++; Fail "Gate-A" "run $i : no evidence file"; continue }
        $db = Get-Content $dbFile -Raw -Encoding UTF8 | ConvertFrom-Json

        $gateA.assertions++
        if ($db.agentRunLatest -and $db.agentRunLatest.status -eq "SUCCESS") { Pass "run $i : agent_run SUCCESS" }
        else { $gateA.failed++; Fail "Gate-A" "run $i : agent_run status = $($db.agentRunLatest.status)" }

        $agent = $db.comments | Where-Object { $_.authorType -eq "AGENT" } | Select-Object -First 1
        $gateA.assertions++
        if ($agent) { Pass "run $i : Agent comment written (id $($agent.id))" }
        else { $gateA.failed++; Fail "Gate-A" "run $i : no AGENT comment" }

        # A7: reply must contain content that exists only in the post body.
        $gateA.assertions++
        if ($agent -and $agent.content -match "亚历山大") { Pass "run $i : A7 reply contains post body" ; $a7ok = $true }
        else { $gateA.failed++; Fail "Gate-A" "run $i : A7 reply missing post body"; $a7ok = $false }
        $gateA.runs += [ordered]@{ i = $i; status = $db.agentRunLatest.status; a7 = $a7ok }
    }

    # A9 idempotency
    Write-Host "`n--- A9 idempotency ---" -ForegroundColor Cyan
    $gateA.assertions++
    $trig = $null
    if (Test-Path (Join-Path $Out "db_A1.json")) {
        $d1 = Get-Content (Join-Path $Out "db_A1.json") -Raw -Encoding UTF8 | ConvertFrom-Json
        $trig = $d1.agentRunLatest.triggerCommentId
    }
    if ($trig) {
        $cnt = (& "$JDK\java.exe" -cp "$Tools;$JDBC" AcceptanceDb count $trig).Trim()
        if ($cnt -eq "1") { Pass "A9: exactly 1 run for trigger $trig" }
        else { $gateA.failed++; Fail "Gate-A" "A9: $cnt runs for trigger $trig" }
        $dup = (& "$JDK\java.exe" -cp "$Tools;$JDBC" AcceptanceDb dupcheck $trig 2>&1 | Out-String).Trim()
        $gateA.assertions++
        if ($dup -match "REJECTED") { Pass "A9: duplicate run rejected by UNIQUE key" }
        else { $gateA.failed++; Fail "Gate-A" "A9: duplicate run was NOT rejected ($dup)" }
    } else { $gateA.failed++; Fail "Gate-A" "A9: no trigger id to assert" }

    $gateA.status = if ($gateA.failed -gt 0) { "FAIL" } else { "PASS" }
    $results.gates += $gateA

    # ---------------------------------------------------------------- Gate B / C
    Write-Host "`n=== Gate B / C: session isolation & reuse ===" -ForegroundColor Cyan
    $gateBC = [ordered]@{ id = "Gate-BC"; status = "SKIP"; assertions = 0; failed = 0; note = "Pithagoras session assertions require portal login; implemented as API query" }
    try {
        $pw = ((Get-Content "$Pith\.env" -Raw) -split "`n" | Where-Object { $_ -match "^PORTAL_PASSWORD=" }) -split "=",2
        $pw = $pw[1].Trim().Trim('"').Trim("'")
        $null = Invoke-RestMethod -Uri "$PortalUrl/api/auth/login" -Method Post -Body (@{password=$pw}|ConvertTo-Json) -ContentType "application/json" -SessionVariable sess -ErrorAction Stop
        $sessions = (Invoke-RestMethod -Uri "$PortalUrl/api/agent/sessions" -WebSession $sess).sessions
        $sessions | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $Out "sessions.json") -Encoding UTF8
        $keysA = $sessions | Where-Object { $_.channel_key -eq "zhiguang:post:$PostA" }
        $gateBC.assertions++
        if ($keysA) { Pass "Gate C: post:$PostA maps to one session ($($keysA[0].id))" }
        else { $gateBC.failed++; Fail "Gate-BC" "no session for post:$PostA" }
        $gateBC.status = if ($gateBC.failed -gt 0) { "FAIL" } else { "PASS" }
    } catch {
        Info "Gate B/C portal query failed: $($_.Exception.Message) — staying SKIP"
    }
    $results.gates += $gateBC

    # Gate B (cross-post isolation) is reported separately and honestly:
    # it needs a second test post, so until then it stays SKIP — never PASS.
    $gateB = [ordered]@{
        id = "Gate-B"; status = "SKIP"; assertions = 0; failed = 0
        note = "cross-post isolation NOT asserted (needs a second test post). SKIP != PASS."
    }
    $results.gates += $gateB

    # ---------------------------------------------------------------- summary
    $overall = "PASS"
    foreach ($g in $results.gates) { if ($g.status -eq "FAIL") { $overall = "FAIL" } }
    # Any SKIP means the mandatory set (Gate 0 + A + B + C) is not fully proven.
    if ($results.gates | Where-Object { $_.status -eq "SKIP" }) { $overall = "FAIL" }
    $results.overall = $overall

    $sum = @()
    $sum += "# Acceptance Summary — $Stamp"
    $sum += ""
    $sum += "Overall: **$overall**"
    $sum += ""
    foreach ($g in $results.gates) { $sum += "$($g.id): $($g.status)  (assertions $($g.assertions), failed $($g.failed))" }
    $sum += ""
    $sum += "## Failures"
    if ($results.failures.Count -eq 0) { $sum += "- none" } else { $results.failures | ForEach-Object { $sum += "- $_" } }
    $sum += ""
    $sum += "> Note: SKIP is never reported as PASS (acceptance 16)."
    $sum -join "`n" | Set-Content (Join-Path $Out "summary.md") -Encoding UTF8
    $results | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $Out "results.json") -Encoding UTF8

    Write-Host "`n=== RESULT ===" -ForegroundColor Cyan
    foreach ($g in $results.gates) { Write-Host ("{0}: {1}" -f $g.id, $g.status) }
    Write-Host "OVERALL: $overall"
    Write-Host "Evidence: $Out"
}
catch {
    Write-Host "[ERROR] acceptance script exception: $($_.Exception.Message)" -ForegroundColor Red
    exit 3
}
finally {
    Write-Host "`n--- cleanup ---" -ForegroundColor Yellow
    try {
        & "$JDK\java.exe" -cp "$Tools;$JDBC" AcceptanceDb cleanup $PostA 2>&1 | Out-File (Join-Path $Out "cleanup.txt") -Encoding UTF8
        Write-Host "cleanup done (see cleanup.txt)"
    } catch { Write-Host "cleanup failed: $($_.Exception.Message)" -ForegroundColor Red }
}

if ($results.overall -eq "PASS") { exit 0 } else { exit 1 }
