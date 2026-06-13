<#
.SYNOPSIS
    Open all kubectl port-forwards needed for the CircleGuard demo (kind cluster 'circleguard').
.DESCRIPTION
    Run this in its OWN PowerShell window before recording. It opens loopback port-forwards to the
    app services and the observability UIs, prints the URLs, and tears everything down when you
    press Enter. Requires the cluster to be up (see docs/DEMO_RUNBOOK.md).
#>
$ErrorActionPreference = 'SilentlyContinue'

$forwards = @(
    @{ns='circleguard-dev';           svc='circleguard-gateway-service';      local=18087; remote=8080; label='Gateway API        '}
    @{ns='circleguard-dev';           svc='circleguard-auth-service';         local=18080; remote=8080; label='Auth API          '}
    @{ns='circleguard-dev';           svc='circleguard-identity-service';     local=18081; remote=8080; label='Identity API       '}
    @{ns='circleguard-dev';           svc='circleguard-form-service';         local=18086; remote=8080; label='Form API          '}
    @{ns='circleguard-dev';           svc='circleguard-promotion-service';    local=18088; remote=8080; label='Promotion API      '}
    @{ns='circleguard-observability'; svc='grafana';                          local=3000;  remote=3000; label='Grafana (admin/admin)'}
    @{ns='circleguard-observability'; svc='prometheus';                       local=9090;  remote=9090; label='Prometheus         '}
    @{ns='circleguard-observability'; svc='jaeger';                           local=16686; remote=16686; label='Jaeger (traces)    '}
    @{ns='circleguard-observability'; svc='alertmanager';                     local=9093;  remote=9093; label='Alertmanager       '}
)

$jobs = @()
foreach ($f in $forwards) {
    $jobs += Start-Job -ScriptBlock {
        param($ns,$svc,$l,$r)
        kubectl port-forward --address=127.0.0.1 -n $ns "svc/$svc" "$($l):$r"
    } -ArgumentList $f.ns,$f.svc,$f.local,$f.remote
}

Start-Sleep -Seconds 7
Write-Host "`n=== CircleGuard demo — port-forwards up ===`n" -ForegroundColor Green
foreach ($f in $forwards) { Write-Host ("  {0}  ->  http://127.0.0.1:{1}" -f $f.label, $f.local) }
Write-Host "`n  Grafana dashboard: 'CircleGuard — Overview' (login admin/admin)" -ForegroundColor Cyan
Write-Host "  Mint a QR token for a GREEN/RED gate demo:  ./scripts/demo-qr-token.ps1`n" -ForegroundColor Cyan

Read-Host "Press Enter to STOP all port-forwards"
$jobs | Stop-Job
$jobs | Remove-Job -Force
Write-Host "Port-forwards stopped." -ForegroundColor Yellow
