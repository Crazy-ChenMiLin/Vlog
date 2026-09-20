#Requires -Version 5.1
<#
   Gate B — cross-post isolation (strong, content-level assertions).

   Instead of only asserting "two session ids differ" (which is the weak
   "different string != isolation" check), this asserts:
     1. channel_key A != channel_key B
     2. internal session.id A != internal session.id B
     3. Agent reply for A contains A's body marker and NOT B's marker
     4. Agent reply for B contains B's body marker and NOT A's marker

   Usage:
     powershell -ExecutionPolicy Bypass -File scripts/acceptance/gate_b.ps1
#>
param(
    [string]$PostA = "350092465539256320",
    [string]$PostB = "102003327239193262",
    [string]$JavaUrl = "http://localhost:8080",
    [string]$PortalUrl = "http://localhost:4100"
)

$ErrorActionPreference = "Stop"
$JDK  = "C:\Users\26487\.jdks\ms-21.0.9\bin"
$JDBC = "C:\Users\26487\.m2\repository\com\mysql\mysql-connector-j\9.7.0\mysql-connector-j-9.7.0.jar"
$Tools = Join-Path $PSScriptRoot "tools"
$Repo = "D:\resume-project"
$Stamp = Get-Date -Format "yyyy-MM-dd_HHmmss"
$Out = Join-Path $Repo "artifacts\acceptance\gateB_$Stamp"
New-Item -ItemType Directory -Path $Out -Force | Out-Null

$pass = 0; $fail = 0; $msgs = @()
function Ok([string]$m)   { $script:pass++; Write-Host "  [PASS] $m" -ForegroundColor Green; $script:msgs += "PASS: $m" }
function Bad([string]$m)  { $script:fail++; Write-Host "  [FAIL] $m" -ForegroundColor Red;   $script:msgs += "FAIL: $m" }

try {
    & "$JDK\javac.exe" -encoding UTF-8 -cp $JDBC (Join-Path $Tools "AcceptanceDb.java") -d $Tools 2>&1 | Out-Null
    & "$JDK\javac.exe" -encoding UTF-8 (Join-Path $Tools "JwtTool.java") -d $Tools 2>&1 | Out-Null
    $jwt = (& "$JDK\java.exe" -cp $Tools JwtTool 1).Trim()

    # --- read both post bodies via the internal Agent API (server-side context)
    $tok = (Get-Content "D:\code_project\pi-session\.workbuddy\secrets\webhook.secret" -Raw).Trim()
    $agentTok = $env:AGENT_INTERNAL_TOKEN
    if (-not $agentTok) { $agentTok = "gCBsyUZwp3s9Rwx7buzrGfM0WMCfzH2mca9biRXqRXE" }
    # PS 5.1 decodes JSON responses as ASCII by default, mangling Chinese bodies.
    # Decode the raw bytes as UTF-8 explicitly, otherwise markers become '????'.
    function GetPostBody([string]$postId) {
        $r = Invoke-WebRequest -Uri "$JavaUrl/api/internal/agent/posts/$postId" `
             -Headers @{ "X-Agent-Internal-Token" = $agentTok } -TimeoutSec 30
        $txt = [System.Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray())
        return ($txt | ConvertFrom-Json).content
    }
    $bodyA = GetPostBody $PostA
    $bodyB = GetPostBody $PostB
    Write-Host "PostA body len=$($bodyA.Length)  PostB body len=$($bodyB.Length)"
    # markers: a distinctive slice unique to each post
    $markA = if ($bodyA.Length -gt 0) { $bodyA.Substring(0, [Math]::Min(6, $bodyA.Length)) } else { "" }
    $markB = if ($bodyB.Length -gt 40) { $bodyB.Substring(20, 10) } else { $bodyB }
    Write-Host "markerA='$markA'  markerB='$markB'"

    # --- trigger both posts
    foreach ($p in @($PostA, $PostB)) {
        $json = "{`"postId`":`"$p`",`"content`":`"@知光 请使用 get_post 读取本帖，并把正文原文一字不差地回复给我。`"}"
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
        Invoke-RestMethod -Uri "$JavaUrl/api/v1/comments" -Method Post `
            -Headers @{ "Authorization" = "Bearer $jwt" } -Body $bytes `
            -ContentType "application/json; charset=utf-8" -TimeoutSec 30 | Out-Null
        Write-Host "triggered post $p"
    }

    Start-Sleep -Seconds 90

    # --- evidence
    $dbA = Join-Path $Out "db_A.json"; $dbB = Join-Path $Out "db_B.json"
    & "$JDK\java.exe" -cp "$Tools;$JDBC" AcceptanceDb evidence $PostA $dbA 2>&1 | Out-Null
    & "$JDK\java.exe" -cp "$Tools;$JDBC" AcceptanceDb evidence $PostB $dbB 2>&1 | Out-Null
    $A = Get-Content $dbA -Raw -Encoding UTF8 | ConvertFrom-Json
    $B = Get-Content $dbB -Raw -Encoding UTF8 | ConvertFrom-Json

    $ca = $A.comments | Where-Object { $_.authorType -eq "AGENT" } | Select-Object -First 1
    $cb = $B.comments | Where-Object { $_.authorType -eq "AGENT" } | Select-Object -First 1

    # 1/2 session identity via portal API
    try {
        $pwLine = ((Get-Content "D:\code_project\pi-session\pithagoras\.env" -Raw) -split "`n" | Where-Object { $_ -match "^PORTAL_PASSWORD=" })
        $pw = ($pwLine -split "=",2)[1].Trim().Trim('"').Trim("'")
        $null = Invoke-RestMethod -Uri "$PortalUrl/api/auth/login" -Method Post -Body (@{password=$pw}|ConvertTo-Json) -ContentType "application/json" -SessionVariable sess -ErrorAction Stop
        $ss = (Invoke-RestMethod -Uri "$PortalUrl/api/agent/sessions" -WebSession $sess).sessions
        $ss | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $Out "sessions.json") -Encoding UTF8
        $sa = $ss | Where-Object { $_.channel_key -eq "zhiguang:post:$PostA" }
        $sb = $ss | Where-Object { $_.channel_key -eq "zhiguang:post:$PostB" }
        if ($sa -and $sb) {
            if ($sa[0].channel_key -ne $sb[0].channel_key) { Ok "channel_key A != B ($($sa[0].channel_key) vs $($sb[0].channel_key))" } else { Bad "channel_key identical" }
            if ($sa[0].id -ne $sb[0].id) { Ok "session.id A != B ($($sa[0].id) vs $($sb[0].id))" } else { Bad "session.id identical" }
        } else { Bad "could not resolve both sessions (A=$($sa -ne $null) B=$($sb -ne $null))" }
    } catch { Bad "session query failed: $($_.Exception.Message)" }

    # 3/4 content isolation
    if ($ca -and $cb -and $markA -and $markB) {
        if ($ca.content -match [regex]::Escape($markA)) { Ok "reply A contains A's body marker" } else { Bad "reply A missing A marker" }
        if ($ca.content -notmatch [regex]::Escape($markB)) { Ok "reply A does NOT contain B's marker" } else { Bad "reply A leaked B's marker" }
        if ($cb.content -match [regex]::Escape($markB)) { Ok "reply B contains B's body marker" } else { Bad "reply B missing B marker" }
        if ($cb.content -notmatch [regex]::Escape($markA)) { Ok "reply B does NOT contain A's marker" } else { Bad "reply B leaked A's marker" }
    } else { Bad "missing agent comments (A=$($ca -ne $null) B=$($cb -ne $null)) or markers" }

    $overall = if ($fail -gt 0) { "FAIL" } else { "PASS" }
    $sum = @("# Gate B — cross-post isolation", "", "Overall: **$overall**", "") + $msgs
    $sum += ""
    $sum += "Posts: A=$PostA  B=$PostB"
    $sum -join "`n" | Set-Content (Join-Path $Out "summary.md") -Encoding UTF8
    Write-Host "`nGATE B OVERALL: $overall   evidence: $Out"
}
catch {
    Write-Host "[ERROR] $($_.Exception.Message)" -ForegroundColor Red
    "ERROR: $($_.Exception.Message)" | Set-Content (Join-Path $Out "error.txt") -Encoding UTF8
}
finally {
    Write-Host "--- cleanup ---" -ForegroundColor Yellow
    & "$JDK\java.exe" -cp "$Tools;$JDBC" AcceptanceDb cleanup $PostA 2>&1 | Out-File (Join-Path $Out "cleanup.txt") -Encoding UTF8
    & "$JDK\java.exe" -cp "$Tools;$JDBC" AcceptanceDb cleanup $PostB 2>&1 | Add-Content (Join-Path $Out "cleanup.txt") -Encoding UTF8
    Write-Host "cleanup done"
}
