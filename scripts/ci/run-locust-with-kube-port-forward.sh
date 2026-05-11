#!/usr/bin/env bash
# Open kubectl port-forwards (same local ports as run-e2e-with-kube-port-forward.sh), set TARGET_*
# for tests/performance/locustfile.py, then exec the Locust command (venv binary or locust).
#
# Usage:
#   bash scripts/ci/run-locust-with-kube-port-forward.sh circleguard-stage \
#     /path/to/.jenkins-locust-venv/bin/locust -f tests/performance/locustfile.py --headless ...
#
set -eu

NS="${1:?namespace required (e.g. circleguard-stage)}"
shift

PF_AUTH=""
PF_ID=""
PF_FORM=""
PF_PROM=""
PF_GW=""

cleanup() {
  for pid in "${PF_AUTH}" "${PF_ID}" "${PF_FORM}" "${PF_PROM}" "${PF_GW}"; do
    if [[ -n "${pid}" ]] && kill -0 "${pid}" 2>/dev/null; then
      kill "${pid}" 2>/dev/null || true
    fi
  done
}

trap cleanup EXIT INT TERM

echo "[INFO] Locust: kubectl port-forward into namespace ${NS} (127.0.0.1:18080–18088)..."

PF_COMMON=(port-forward --address=127.0.0.1 -n "${NS}")
kubectl "${PF_COMMON[@]}" svc/circleguard-auth-service 18080:8080 >/tmp/cg-locust-pf-auth.log 2>&1 &
PF_AUTH=$!
sleep 0.4
kubectl "${PF_COMMON[@]}" svc/circleguard-identity-service 18081:8080 >/tmp/cg-locust-pf-identity.log 2>&1 &
PF_ID=$!
sleep 0.4
kubectl "${PF_COMMON[@]}" svc/circleguard-form-service 18086:8080 >/tmp/cg-locust-pf-form.log 2>&1 &
PF_FORM=$!
sleep 0.4
kubectl "${PF_COMMON[@]}" svc/circleguard-promotion-service 18088:8080 >/tmp/cg-locust-pf-promotion.log 2>&1 &
PF_PROM=$!
sleep 0.4
kubectl "${PF_COMMON[@]}" svc/circleguard-gateway-service 18087:8080 >/tmp/cg-locust-pf-gateway.log 2>&1 &
PF_GW=$!
sleep 2

wait_tcp() {
  local port="$1"
  local label="$2"
  local n=0
  while [[ "${n}" -lt 90 ]]; do
    if bash -c "echo >/dev/tcp/127.0.0.1/${port}" 2>/dev/null; then
      echo "[INFO] Locust PF: port ${port} (${label}) open."
      return 0
    fi
    sleep 1
    n=$((n + 1))
  done
  echo "[ERROR] Locust: timeout waiting for port ${port} (${label}). Log:"
  cat "/tmp/cg-locust-pf-${label}.log" 2>/dev/null || true
  return 1
}

wait_tcp 18080 auth
wait_tcp 18081 identity
wait_tcp 18086 form
wait_tcp 18087 gateway
wait_tcp 18088 promotion

export TARGET_AUTH="http://127.0.0.1:18080"
export TARGET_FORM="http://127.0.0.1:18086"
export TARGET_GATEWAY="http://127.0.0.1:18087"
export TARGET_PROMOTION="http://127.0.0.1:18088"

echo "[INFO] Locust TARGET_* -> auth form gateway promotion on 18080 18086 18087 18088"

exec "$@"
