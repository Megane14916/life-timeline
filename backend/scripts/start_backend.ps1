param(
    [Parameter(Mandatory = $true)]
    [string]$DataDir,
    [switch]$Reload
)

$ErrorActionPreference = 'Stop'

if (-not [System.IO.Path]::IsPathRooted($DataDir)) {
    throw 'DataDir must be an absolute path.'
}

$backendRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
New-Item -ItemType Directory -Force -Path $DataDir | Out-Null
$resolvedDataDir = (Resolve-Path -LiteralPath $DataDir).Path
$env:LIFE_TIMELINE_DATA_DIR = $resolvedDataDir

Push-Location $backendRoot
try {
    & uv run alembic upgrade head
    if ($LASTEXITCODE -ne 0) {
        throw "Database migration failed with exit code $LASTEXITCODE."
    }

    $uvicornArguments = @(
        'run',
        'uvicorn',
        'app.main:app',
        '--host',
        '127.0.0.1',
        '--port',
        '8000'
    )
    if ($Reload) {
        $uvicornArguments += '--reload'
    }
    & uv @uvicornArguments
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
