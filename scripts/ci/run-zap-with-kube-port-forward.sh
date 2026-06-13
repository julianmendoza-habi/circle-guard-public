#!/usr/bin/env bash
# Open a local kubectl port-forward to the gateway in a Kubernetes namespace and run an OWASP ZAP
# baseline DAST scan against it. Intended for the Jenkins-in-Docker stack (docker-compose.jenkins.yml),
# mirroring scripts/ci/run-e2e-with-kube-port-forward.sh.
#
# Usage: ./scripts/ci/run-zap-with-kube-port-forward.sh <namespace> [local_port]
# Example: ./scripts/ci/run-zap-with-kube-port-forward.sh circleguard-dev
set -eu

NS="${1:?namespace required (e.g. circleguard-dev)}"
PORT="${2:-18087}"
HERE="$(cd "$(dirname "$0")" && pwd)"

PF_GW=""
cleanup() {
  if [ -n "${PF_GW}" ] && kill -0 "${PF_GW}" 2>/dev/null; then
    kill "${PF_GW}" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

echo "[zap-pf] kubectl port-forward svc/circleguard-gateway-service ${PORT}:8080 -n ${NS} (loopback only)"
kubectl port-forward --address=127.0.0.1 -n "${NS}" svc/circleguard-gateway-service "${PORT}:8080" \
  >/tmp/cg-pf-zap.log 2>&1 &
PF_GW=$!

# Wait for the tunnel to accept connections (pod may still be starting).
n=0
while [ "${n}" -lt 90 ]; do
  if bash -c "echo >/dev/tcp/127.0.0.1/${PORT}" 2>/dev/null; then
    echo "[zap-pf] gateway port ${PORT} is accepting connections."
    break
  fi
  sleep 1
  n=$((n + 1))
done
if [ "${n}" -ge 90 ]; then
  echo "[zap-pf] ERROR: timeout waiting for gateway port-forward. Last kubectl log:" >&2
  cat /tmp/cg-pf-zap.log 2>/dev/null || true
  exit 1
fi

exec "${HERE}/zap-baseline.sh" "http://127.0.0.1:${PORT}"
