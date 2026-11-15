param([string]$DatabasePath = 'data/database.bin')

$ErrorActionPreference = 'Stop'
$utf8 = New-Object System.Text.UTF8Encoding($false)
chcp.com 65001 | Out-Null
[Console]::InputEncoding = $utf8
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8

Push-Location $PSScriptRoot
try {
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
        throw 'Для запуска требуется Java 8 или новее.'
    }
    if (Test-Path -LiteralPath 'outputs/minidb-ru.jar') {
        & java -jar 'outputs/minidb-ru.jar' $DatabasePath
    } else {
        & './build.ps1'
        & java -cp out MiniDb $DatabasePath
    }
    if ($LASTEXITCODE -ne 0) { throw "Программа завершилась с кодом $LASTEXITCODE." }
} finally {
    Pop-Location
}
