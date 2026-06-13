<#
.SYNOPSIS
    Deploy the full CircleGuard stack to a local kind cluster (app + infra + observability).
.DESCRIPTION
    Reproducible local deployment used for the demo. Steps:
      1. Create kind cluster 'circleguard' (if missing).
      2. Build the 6 service fat JARs (inside a gradle:8.14-jdk21 container — the native gradlew is
         loopback-blocked on this host; see HANDOFF.md §4).
      3. Build the 6 Docker images and load them into the kind node via `kind load image-archive`
         (docker save | load — reliable with Docker's containerd image store, unlike
         `kind load docker-image`).
      4. Apply manifests in order: namespaces -> infra -> ensure-databases -> apps -> observability.
    Re-runnable: existing cluster/images are reused. Requires Docker Desktop running + kind + kubectl.

    After it finishes, run ./scripts/demo-port-forwards.ps1 and follow docs/DEMO_RUNBOOK.md.
.PARAMETER SkipBuild
    Skip the JAR + image build (use already-built/loaded images).
#>
param([switch]$SkipBuild)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path "$PSScriptRoot\..").Path
$cluster = 'circleguard'
$services = 'auth-service','identity-service','form-service','promotion-service','notification-service','gateway-service'

function Step($m) { Write-Host "`n=== $m ===" -ForegroundColor Cyan }

# 1) Cluster ----------------------------------------------------------------------------------
Step "Ensuring kind cluster '$cluster'"
if ((kind get clusters 2>$null) -notcontains $cluster) {
    kind create cluster --name $cluster --wait 150s
} else { Write-Host "cluster already exists" }
kubectl config use-context "kind-$cluster" | Out-Null

if (-not $SkipBuild) {
    # 2) Fat JARs (in container) --------------------------------------------------------------
    Step "Building 6 service fat JARs (gradle container)"
    $tasks = ($services | ForEach-Object { ":services:circleguard-$_:bootJar" }) -join ' '
    $g = "gradle --no-daemon --console=plain --project-cache-dir /tmp/pc $tasks -x test 2>&1"
    docker run --rm --user root -e GRADLE_USER_HOME=/home/gradle/.gradle `
        -v "$($repo):/app" -w /app -v cg-gradle-home:/home/gradle/.gradle -v cg-pcache:/tmp/pc `
        gradle:8.14-jdk21 bash -lc $g | Select-Object -Last 2
    if ($LASTEXITCODE -ne 0) { throw "gradle bootJar build failed" }

    # 3) Images + load into node --------------------------------------------------------------
    Step "Building + loading 6 images into the kind node"
    Push-Location $repo
    foreach ($s in $services) {
        docker build -q -f docker/Dockerfile.service --build-arg SERVICE_DIR="circleguard-$s" -t "circleguard/${s}:dev-latest" . | Out-Null
        $tar = Join-Path $env:TEMP "$s.tar"
        docker save "circleguard/${s}:dev-latest" -o $tar
        kind load image-archive $tar --name $cluster | Out-Null
        Write-Host "  loaded circleguard/${s}:dev-latest"
    }
    Pop-Location
}

# 4) Manifests --------------------------------------------------------------------------------
Step "Applying namespaces + infrastructure"
kubectl apply -f deploy/k8s/namespaces.yaml
kubectl apply -f deploy/k8s/infra/postgres-redis-neo4j.yaml
kubectl apply -f deploy/k8s/infra/kafka-zookeeper.yaml
kubectl apply -f deploy/k8s/infra/openldap.yaml

Step "Waiting for PostgreSQL, then creating per-service databases"
kubectl rollout status deployment/postgres -n circleguard-infra --timeout=300s
kubectl delete job postgres-ensure-databases -n circleguard-infra --ignore-not-found | Out-Null
kubectl apply -f deploy/k8s/infra/postgres-ensure-databases.yaml
kubectl wait --for=condition=complete job/postgres-ensure-databases -n circleguard-infra --timeout=180s

Step "Deploying microservices"
kubectl apply -f deploy/k8s/apps/dev/microservices.yaml

Step "Deploying observability + logging"
kubectl apply -f deploy/k8s/infra/observability.yaml
kubectl apply -f deploy/k8s/infra/logging.yaml

Step "Waiting for the 6 microservices to become Ready"
foreach ($s in $services) {
    kubectl rollout status "deployment/circleguard-$s" -n circleguard-dev --timeout=300s
}

Step "Done — cluster state"
kubectl get pods -A | Select-String 'circleguard'
Write-Host "`nNext: ./scripts/demo-port-forwards.ps1  then follow docs/DEMO_RUNBOOK.md" -ForegroundColor Green
