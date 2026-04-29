# Generates RELEASE_NOTES.md from git log since last tag (Conventional-Commit style when possible).
# Usage: ./scripts/generate-release-notes.ps1 -Version "1.0.0" -OutputPath "build/RELEASE_NOTES.md"

param(
    [Parameter(Mandatory = $true)][string]$Version,
    [Parameter(Mandatory = $false)][string]$OutputPath = "RELEASE_NOTES.md"
)

$ErrorActionPreference = "Stop"
$lastTag = git describe --tags --abbrev=0 2>$null
if (-not $lastTag) {
    $range = "HEAD"
    Write-Warning "No git tags found; including all commits."
} else {
    $range = "$lastTag..HEAD"
}

$date = Get-Date -Format "yyyy-MM-dd"
$commits = git log $range --pretty=format:"- %s (%h)" 2>$null
if (-not $commits) {
    $commits = "- No new commits since last tag."
}

$content = @"
# Release $Version — $date

## Summary
Automated release notes for CircleGuard microservices (Change Management).

## Changes since $(if ($lastTag) { $lastTag } else { 'repository start' })
$commits

## Deployment notes
- Verify Kubernetes namespaces and image tags before rollout.
- Run integration tests with ``-Pintegration`` and E2E with ``E2E_RUN=true``.
- Rollback: ``kubectl rollout undo deployment/<name> -n circleguard-master``

## Risk & testing
- Performance: review Locust HTML report (p95 latency, RPS, failure rate).
"@

Set-Content -Path $OutputPath -Value $content -Encoding UTF8
Write-Host "Wrote $OutputPath"
