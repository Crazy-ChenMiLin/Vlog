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
[System.Reflection.Assembly]::LoadWithPartialName('System.IO.Compression') | Out-Null
[System.Reflection.Assembly]::LoadWithPartialName('System.IO.Compression.FileSystem') | Out-Null

$utf8 = [System.Text.UTF8Encoding]::new($false)
$zip = [System.IO.Compression.ZipFile]::Open($archive, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    # Nacos' importer requires '/' even when this script runs on Windows.
    $entries = @(
        @{ Name = '.metadata.yml'; Path = (Join-Path $source '.metadata.yml') },
        @{ Name = 'ZHIGUANG_GROUP/zhiguang-runtime.yaml'; Path = (Join-Path $source 'ZHIGUANG_GROUP\zhiguang-runtime.yaml') }
    )
    foreach ($entryInfo in $entries) {
        $entry = $zip.CreateEntry($entryInfo.Name)
        $writer = [System.IO.StreamWriter]::new($entry.Open(), $utf8)
        try {
            $writer.Write([System.IO.File]::ReadAllText($entryInfo.Path))
        } finally {
            $writer.Dispose()
        }
    }
} finally {
    $zip.Dispose()
}
Write-Host "Created $archive"
