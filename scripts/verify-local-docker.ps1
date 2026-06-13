<#
.SYNOPSIS
    Runs Gradle inside a Linux JDK 21 container to verify builds/tests locally on this machine.

.DESCRIPTION
    The native `gradlew` fails on this Windows host because the JVM's Selector.open() uses an
    AF_UNIX socket pair that local endpoint-security software blocks
    ("Unable to establish loopback connection"). Plain TCP loopback works; only the NIO AF_UNIX
    pipe is blocked, and it breaks the Gradle daemon/workers for ANY local build.

    Running the build inside Linux (where AF_UNIX is fine) sidesteps it entirely, and the
    gradle:8.14-jdk21 image also provides the correct JDK 21 toolchain. This is effectively a
    local mirror of the CI Linux build. Requires Docker Desktop running.

.EXAMPLE
    ./scripts/verify-local-docker.ps1
        # default: runs `test` for the whole project

.EXAMPLE
    ./scripts/verify-local-docker.ps1 :services:circleguard-auth-service:test --tests "*IdentityClientFallbackTest"

.EXAMPLE
    ./scripts/verify-local-docker.ps1 build -x test
#>
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$GradleArgs)

if (-not $GradleArgs) { $GradleArgs = @('test') }

$repo  = (Resolve-Path "$PSScriptRoot\..").Path
# --project-cache-dir keeps Gradle's project cache inside the container so it never collides with
# the Windows-created .gradle dir. The named volume caches downloaded dependencies across runs.
$inner = "gradle --no-daemon --console=plain --project-cache-dir /tmp/pc " + ($GradleArgs -join ' ') + " 2>&1"

Write-Host "[verify-local-docker] gradle $($GradleArgs -join ' ')" -ForegroundColor Cyan
docker run --rm --user root `
    -e GRADLE_USER_HOME=/home/gradle/.gradle `
    -v "${repo}:/app" -w /app `
    -v cg-gradle-home:/home/gradle/.gradle `
    gradle:8.14-jdk21 bash -lc $inner

exit $LASTEXITCODE
