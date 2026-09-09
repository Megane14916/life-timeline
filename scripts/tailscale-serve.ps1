[CmdletBinding()]
param(
    [ValidateSet('status', 'configure', 'check', 'disable')]
    [string]$Action = 'status',
    [ValidateRange(1, 65535)]
    [int]$LocalPort = 8000,
    [string]$Endpoint
)

$ErrorActionPreference = 'Stop'

function Get-TailscaleExecutable {
    $command = Get-Command tailscale -ErrorAction SilentlyContinue
    if ($null -eq $command) {
        throw 'Tailscale CLI is not installed or is not available on PATH. Install and sign in to Tailscale first.'
    }
    return $command.Source
}

function Assert-LoopbackBackend {
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $LocalPort -ErrorAction SilentlyContinue)
    if ($listeners.Count -eq 0) {
        throw "No backend is listening on port $LocalPort. Start FastAPI on 127.0.0.1:$LocalPort first."
    }

    $nonLoopback = @($listeners | Where-Object { $_.LocalAddress -ne '127.0.0.1' })
    if ($nonLoopback.Count -gt 0) {
        $addresses = ($nonLoopback | Select-Object -ExpandProperty LocalAddress -Unique) -join ', '
        throw "Backend port $LocalPort is not loopback-only. Listening addresses: $addresses"
    }
}

function Assert-Endpoint {
    param([Parameter(Mandatory = $true)][string]$Value)

    try {
        $uri = [Uri]$Value.Trim()
    }
    catch {
        throw 'Endpoint must be a valid HTTPS URL.'
    }

    if ($uri.Scheme -ne 'https' -or [string]::IsNullOrWhiteSpace($uri.Host)) {
        throw 'Endpoint must use HTTPS and include a hostname.'
    }
    if ($null -ne $uri.UserInfo -and $uri.UserInfo.Length -gt 0) {
        throw 'Endpoint must not include userinfo.'
    }
    if ($null -ne $uri.Query -and $uri.Query.Length -gt 0) {
        throw 'Endpoint must not include a query string.'
    }
    if ($null -ne $uri.Fragment -and $uri.Fragment.Length -gt 0) {
        throw 'Endpoint must not include a fragment.'
    }
    return $uri
}

$tailscale = Get-TailscaleExecutable

switch ($Action) {
    'status' {
        & $tailscale serve status
    }
    'configure' {
        Assert-LoopbackBackend
        & $tailscale serve --bg $LocalPort
        if ($LASTEXITCODE -ne 0) {
            throw "Tailscale Serve configuration failed with exit code $LASTEXITCODE."
        }
        & $tailscale serve status
    }
    'check' {
        Assert-LoopbackBackend
        if ([string]::IsNullOrWhiteSpace($Endpoint)) {
            throw 'Endpoint is required for the check action.'
        }
        $uri = Assert-Endpoint $Endpoint
        $healthUri = "{0}api/v1/health" -f $uri.AbsoluteUri.TrimEnd('/') + '/'
        try {
            $response = Invoke-WebRequest -Uri $healthUri -TimeoutSec 10
            $payload = $response.Content | ConvertFrom-Json
        }
        catch {
            throw "Tailscale HTTPS health check failed: $($_.Exception.Message)"
        }
        if ($response.StatusCode -ne 200 -or $payload.status -ne 'ok') {
            throw "Unexpected health response: HTTP $($response.StatusCode)."
        }
        Write-Output 'Tailscale HTTPS health check passed.'
    }
    'disable' {
        & $tailscale serve off
        if ($LASTEXITCODE -ne 0) {
            throw "Tailscale Serve disable failed with exit code $LASTEXITCODE."
        }
        Write-Output 'Tailscale Serve disabled.'
    }
}
