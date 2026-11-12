$ErrorActionPreference = 'Stop'

$compiler = (Get-Command javac -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty Source)
if (-not $compiler) {
    $jetBrains = 'C:\Program Files\JetBrains'
    if (Test-Path -LiteralPath $jetBrains) {
        $compiler = Get-ChildItem -LiteralPath $jetBrains -Directory |
            ForEach-Object { Join-Path $_.FullName 'jbr\bin\javac.exe' } |
            Where-Object { Test-Path -LiteralPath $_ } |
            Select-Object -First 1
    }
}
if (-not $compiler) { throw 'Для сборки требуется JDK 8 или новее с компилятором javac.' }

New-Item -ItemType Directory -Force -Path 'out' | Out-Null
$versionText = (& $compiler -version 2>&1) -join ' '
if ($versionText -match 'javac\s+1\.8(\.|\s|$)') {
    & $compiler -source 8 -target 8 -encoding UTF-8 -d out src/MiniDb.java src/MiniDbTest.java
} else {
    & $compiler --release 8 -Xlint:-options -encoding UTF-8 -d out src/MiniDb.java src/MiniDbTest.java
}
if ($LASTEXITCODE -ne 0) { throw 'Не удалось скомпилировать программу.' }
Write-Host "Сборка завершена. Компилятор: $compiler"
