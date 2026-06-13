<#
.SYNOPSIS
    Mint a signed QR JWT for the CircleGuard gate demo, and optionally validate it through the gateway.
.DESCRIPTION
    The gateway (QrValidationService) validates QR tokens signed with the HMAC `qr.secret` and looks
    up the subject's health status in Redis. A freshly minted token for a random anonymousId has no
    CONTAGIED/POTENTIAL status, so it validates GREEN ("Welcome to Campus"). Pass -Status to seed a
    RED status in Redis for the subject first.
.PARAMETER Secret
    HMAC secret (must match the deployed qr.secret). Default = dev value.
.PARAMETER Call
    If set, POSTs the token to the gateway at http://127.0.0.1:18087 (needs the port-forward up).
.PARAMETER Status
    Seed this status (e.g. CONTAGIED) in Redis for the subject before validating, to demo a RED result.
.EXAMPLE
    ./scripts/demo-qr-token.ps1 -Call
.EXAMPLE
    ./scripts/demo-qr-token.ps1 -Call -Status CONTAGIED
#>
param(
    [string]$Secret = 'my-qr-secret-key-for-dev-1234567890',
    [switch]$Call,
    [string]$Status,
    [string]$GatewayUrl = 'http://127.0.0.1:18087'
)

function ConvertTo-B64Url([byte[]]$b) { [Convert]::ToBase64String($b).TrimEnd('=').Replace('+','-').Replace('/','_') }

$sub = [guid]::NewGuid().ToString()
$now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$header  = ConvertTo-B64Url ([Text.Encoding]::UTF8.GetBytes('{"alg":"HS256","typ":"JWT"}'))
$payload = ConvertTo-B64Url ([Text.Encoding]::UTF8.GetBytes("{`"sub`":`"$sub`",`"iat`":$now,`"exp`":$($now+300)}"))
$data = "$header.$payload"
$hmac = [System.Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($Secret))
$token = "$data." + (ConvertTo-B64Url $hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($data)))

Write-Host "anonymousId (sub): $sub"
Write-Host "QR token:`n$token`n"

if ($Status) {
    Write-Host "Seeding Redis user:status:$sub = $Status ..." -ForegroundColor Yellow
    kubectl exec -n circleguard-infra deploy/redis -- redis-cli set "user:status:$sub" $Status | Out-Null
}

if ($Call) {
    $body = @{ token = $token } | ConvertTo-Json
    $r = Invoke-RestMethod -Uri "$GatewayUrl/api/v1/gate/validate" -Method POST -Body $body -ContentType 'application/json' -TimeoutSec 20
    $color = if ($r.status -eq 'GREEN') { 'Green' } else { 'Red' }
    Write-Host ("Gate result -> valid={0}  status={1}  message={2}" -f $r.valid, $r.status, $r.message) -ForegroundColor $color
}
