param(
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path,
    [string]$Maven = "",
    [string]$Python = "python",
    [int]$OffPort = 18180,
    [int]$OnPort = 18181,
    [int]$TopK = 10,
    [string]$Questions = (Join-Path $PSScriptRoot "questions-40.json"),
    [string]$RunName = ("rag-ab-" + (Get-Date -Format "yyyyMMdd-HHmmss")),
    [string]$ReportTitle = "RAG BM25 A/B 自动评估",
    [string]$AppendMarkdownTo = "",
    [int]$StartupTimeoutSeconds = 240
)

$ErrorActionPreference = "Stop"

function Resolve-MavenCommand {
    param([string]$ConfiguredMaven)

    if ($ConfiguredMaven -ne "") {
        return $ConfiguredMaven
    }

    $localWindowsMaven = "D:\Maven\apache-maven-3.9.11\bin\mvn.cmd"
    if (Test-Path -LiteralPath $localWindowsMaven) {
        return $localWindowsMaven
    }

    return "mvn"
}

$Maven = Resolve-MavenCommand -ConfiguredMaven $Maven

$TargetDir = Join-Path (Join-Path (Join-Path $RepoRoot "target") "rag-eval") $RunName
$OffOutput = Join-Path $TargetDir "bm25-off.json"
$OnOutput = Join-Path $TargetDir "bm25-on.json"
$CompareJson = Join-Path $TargetDir "compare.json"
$CompareMd = Join-Path $TargetDir "compare.md"
$OffLog = Join-Path $TargetDir "bm25-off-server.log"
$OnLog = Join-Path $TargetDir "bm25-on-server.log"

New-Item -ItemType Directory -Force -Path $TargetDir | Out-Null

Write-Host "Repo root: $RepoRoot"
Write-Host "Maven command: $Maven"
Write-Host "Python command: $Python"
Write-Host "Questions: $Questions"

function Wait-RagServer {
    param([int]$Port, [object]$Job, [string]$LogPath)

    $deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        if ($Job.State -ne "Running") {
            throw "RAG backend job stopped before startup. See $LogPath"
        }
        try {
            Invoke-WebRequest -UseBasicParsing "http://localhost:$Port/api/v1/knowposts/qa/debug?question=ping&topK=1" -TimeoutSec 3 | Out-Null
            return
        } catch {
            if ($_.Exception.Response -ne $null) {
                return
            }
        }
    }
    throw "RAG backend did not become ready in $StartupTimeoutSeconds seconds. See $LogPath"
}

function Invoke-RagEvalPhase {
    param(
        [bool]$Bm25Enabled,
        [int]$Port,
        [string]$OutputPath,
        [string]$LogPath,
        [string]$Label
    )

    Write-Host "Starting backend: bm25-enabled=$Bm25Enabled port=$Port"
    $enabledText = if ($Bm25Enabled) { "true" } else { "false" }
    $job = Start-Job -ScriptBlock {
        param($RepoRoot, $Maven, $Port, $EnabledText, $LogPath)
        Set-Location $RepoRoot
        & $Maven spring-boot:run "-Dspring-boot.run.arguments=--server.port=$Port --rag.retrieval.bm25-enabled=$EnabledText" *> $LogPath
    } -ArgumentList $RepoRoot, $Maven, $Port, $enabledText, $LogPath

    try {
        Wait-RagServer -Port $Port -Job $job -LogPath $LogPath
        Write-Host "Running debug eval: $Label"
        & $Python (Join-Path $PSScriptRoot "run-rag-debug-eval.py") `
            --base-url "http://localhost:$Port" `
            --questions $Questions `
            --output $OutputPath `
            --top-k $TopK `
            --label $Label
    } finally {
        Stop-Job $job -ErrorAction SilentlyContinue
        Remove-Job $job -Force -ErrorAction SilentlyContinue
    }
}

Invoke-RagEvalPhase -Bm25Enabled $false -Port $OffPort -OutputPath $OffOutput -LogPath $OffLog -Label "bm25-off"
Invoke-RagEvalPhase -Bm25Enabled $true -Port $OnPort -OutputPath $OnOutput -LogPath $OnLog -Label "bm25-on"

& $Python (Join-Path $PSScriptRoot "compare-rag-ab.py") `
    --off $OffOutput `
    --on $OnOutput `
    --output-json $CompareJson `
    --output-md $CompareMd `
    --title $ReportTitle

if ($AppendMarkdownTo -ne "") {
    Add-Content -Encoding UTF8 -LiteralPath $AppendMarkdownTo -Value ""
    Add-Content -Encoding UTF8 -LiteralPath $AppendMarkdownTo -Value (Get-Content -Encoding UTF8 -Raw -LiteralPath $CompareMd)
}

Write-Host "RAG A/B evaluation completed."
Write-Host "Output directory: $TargetDir"
Write-Host "Markdown report: $CompareMd"
