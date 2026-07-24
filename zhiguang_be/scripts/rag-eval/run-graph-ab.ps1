param(
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path,
    [string]$Maven = "",
    [string]$Python = "python",
    [int]$OffPort = 18221,
    [int]$OnPort = 18222,
    [int]$TopK = 10,
    [string]$Questions = (Join-Path $PSScriptRoot "questions-relation-cache-100.json"),
    [string]$Spec = (Join-Path $PSScriptRoot "relation-spec-100.json"),
    [string]$RunName = ("graph-ab-" + (Get-Date -Format "yyyyMMdd-HHmmss")),
    [string]$GraphUnderstandingEnabled = "true",
    [int]$StartupTimeoutSeconds = 240,
    [int]$BackendStartupAttempts = 2,
    [int]$BetweenPhaseDelaySeconds = 10,
    [int]$RequestRetries = 3,
    [double]$RetryDelaySeconds = 2.0
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
$OffOutput = Join-Path $TargetDir "graph-off.json"
$OnOutput = Join-Path $TargetDir "graph-on.json"
$OffLog = Join-Path $TargetDir "graph-off-backend.log"
$OnLog = Join-Path $TargetDir "graph-on-backend.log"

New-Item -ItemType Directory -Force -Path $TargetDir | Out-Null

Write-Host "Repo root: $RepoRoot"
Write-Host "Maven command: $Maven"
Write-Host "Python command: $Python"
Write-Host "Questions: $Questions"
Write-Host "Spec: $Spec"
Write-Host "Output directory: $TargetDir"

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

function Invoke-GraphEvalPhase {
    param(
        [bool]$GraphEnabled,
        [int]$Port,
        [string]$OutputPath,
        [string]$LogPath,
        [string]$Label
    )

    $graphText = if ($GraphEnabled) { "true" } else { "false" }
    for ($attempt = 1; $attempt -le $BackendStartupAttempts; $attempt++) {
        Write-Host "Starting backend: graph-enabled=$graphText port=$Port attempt=$attempt/$BackendStartupAttempts"
        $job = Start-Job -ScriptBlock {
            param($RepoRoot, $Maven, $Port, $GraphText, $GraphUnderstandingEnabled, $LogPath)
            Set-Location $RepoRoot
            & $Maven -q spring-boot:run "-Dspring-boot.run.arguments=--server.port=$Port --rag.retrieval.bm25-enabled=true --rag.retrieval.graph-enabled=$GraphText --rag.graph.understanding-enabled=$GraphUnderstandingEnabled --rag.retrieval.candidate-multiplier=2 --rag.retrieval.max-candidates=20 --spring.kafka.listener.auto-startup=false --canal.enabled=false --spring.task.scheduling.enabled=false" *> $LogPath
        } -ArgumentList $RepoRoot, $Maven, $Port, $graphText, $GraphUnderstandingEnabled, $LogPath

        try {
            Wait-RagServer -Port $Port -Job $job -LogPath $LogPath
            Write-Host "Running graph debug eval: $Label"
            & $Python (Join-Path $PSScriptRoot "run-rag-debug-eval.py") `
                --base-url "http://localhost:$Port" `
                --questions $Questions `
                --output $OutputPath `
                --top-k $TopK `
                --label $Label `
                --retries $RequestRetries `
                --retry-delay $RetryDelaySeconds
            return
        } catch {
            if ($attempt -ge $BackendStartupAttempts) {
                throw
            }
            Write-Warning "Backend startup/eval failed for ${Label}: $($_.Exception.Message)"
            Write-Warning "Retrying after $BetweenPhaseDelaySeconds seconds. See $LogPath"
            Start-Sleep -Seconds $BetweenPhaseDelaySeconds
        } finally {
            Stop-Job $job -ErrorAction SilentlyContinue
            Remove-Job $job -Force -ErrorAction SilentlyContinue
        }
    }
}

Invoke-GraphEvalPhase -GraphEnabled $false -Port $OffPort -OutputPath $OffOutput -LogPath $OffLog -Label "graph-off"
Start-Sleep -Seconds $BetweenPhaseDelaySeconds
Invoke-GraphEvalPhase -GraphEnabled $true -Port $OnPort -OutputPath $OnOutput -LogPath $OnLog -Label "graph-on"

& $Python (Join-Path $PSScriptRoot "compare-graph-ab.py") `
    --off $OffOutput `
    --on $OnOutput `
    --spec $Spec `
    --out-dir $TargetDir

Write-Host "Graph A/B evaluation completed."
Write-Host "Output directory: $TargetDir"
Write-Host "Markdown report: $(Join-Path $TargetDir "compare.md")"
