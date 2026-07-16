$ErrorActionPreference = "Stop"

$logPath = Join-Path $PSScriptRoot "gpedit-install.log"
Start-Transcript -Path $logPath -Append | Out-Null

try {
    $isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator
    )
    if (-not $isAdmin) {
        throw "This script must be run from an elevated PowerShell window."
    }

    $packagesDir = Join-Path $env:windir "servicing\Packages"
    $componentVersion = (Get-ItemProperty "HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion").BuildLabEx -replace "^(\d+\.\d+\.\d+\.\d+).*$", '$1'
    if (-not $componentVersion -or $componentVersion -eq (Get-ItemProperty "HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion").BuildLabEx) {
        $ubr = (Get-ItemProperty "HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion").UBR
        $componentVersion = "10.0.26100.$ubr"
    }

    $patterns = @(
        "Microsoft-Windows-GroupPolicy-ClientTools-Package~*$componentVersion.mum",
        "Microsoft-Windows-GroupPolicy-ClientTools-merged-Package~*$componentVersion.mum",
        "Microsoft-Windows-GroupPolicy-ClientExtensions-Package~*$componentVersion.mum",
        "Microsoft-Windows-GroupPolicy-ClientTools-WOW64-Package~*$componentVersion.mum",
        "Microsoft-Windows-GroupPolicy-ClientExtensions-WOW64-Package~*$componentVersion.mum"
    )

    $packages = foreach ($pattern in $patterns) {
        Get-ChildItem -Path $packagesDir -Filter $pattern -File
    }
    $packages = $packages | Sort-Object FullName -Unique

    if (-not $packages) {
        throw "No Group Policy package manifests were found under $packagesDir."
    }

    Write-Host "Target component version: $componentVersion"
    Write-Host "Found $($packages.Count) matching Group Policy package manifest(s)."

    foreach ($package in $packages) {
        Write-Host "Installing $($package.Name)"
        & dism.exe /Online /NoRestart /Add-Package "/PackagePath:$($package.FullName)"
        $exitCode = $LASTEXITCODE
        if ($exitCode -ne 0 -and $exitCode -ne 3010) {
            throw "DISM failed for $($package.FullName) with exit code $exitCode."
        }
    }

    $gpeditPath = Join-Path $env:windir "System32\gpedit.msc"
    if (-not (Test-Path $gpeditPath)) {
        throw "DISM completed, but $gpeditPath was not found."
    }

    Write-Host "gpedit.msc installed at $gpeditPath"
    Write-Host "A restart is recommended if Windows asks for it."
}
finally {
    Stop-Transcript | Out-Null
}
