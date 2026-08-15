$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$source = Join-Path $root 'nacos-import'
$dist = Join-Path $root 'dist'
$archive = Join-Path $dist 'zhiguang-nacos-import.zip'

if (Test-Path $archive) {
    Remove-Item -LiteralPath $archive -Force
}

New-Item -ItemType Directory -Path $dist -Force | Out-Null

# Nacos 3.x requires a root .metadata.yml as well as group/dataId content.
# Pass the hidden metadata file explicitly: PowerShell wildcards can omit it.
$items = @(
    (Join-Path $source '.metadata.yml'),
    (Join-Path $source 'ZHIGUANG_GROUP')
)
Compress-Archive -Path $items -DestinationPath $archive -Force
Write-Host "Created $archive"
