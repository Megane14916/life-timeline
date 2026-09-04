$ErrorActionPreference = 'Stop'

uv run python scripts/smoke.py
if ($LASTEXITCODE -ne 0) {
    throw "Backend smoke test failed with exit code $LASTEXITCODE"
}
