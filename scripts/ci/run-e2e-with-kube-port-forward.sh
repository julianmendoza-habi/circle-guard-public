#!/usr/bin/env bash
# Run :e2e-tests:test against services in a Kubernetes namespace by opening local kubectl
# port-forwards (127.0.0.1). Intended for the Jenkins-in-Docker stack (docker-compose.jenkins.yml):
# no E2E_* variables need to be set in the job.
#
# Usage: ./scripts/ci/run-e2e-with-kube-port-forward.sh <namespace>
# Example: ./scripts/ci/run-e2e-with-kube-port-forward.sh circleguard-master

set -eu

NS="${1:?namespace required (e.g. circleguard-master or circleguard-stage)}"

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

echo "[INFO] Starting kubectl port-forward into namespace ${NS} (local ports 18080–18088)..."

# Bind only loopback; stagger starts so kubectl does not race opening five tunnels at once.
PF_COMMON=(port-forward --address=127.0.0.1 -n "${NS}")
kubectl "${PF_COMMON[@]}" svc/circleguard-auth-service 18080:8080 >/tmp/cg-pf-auth.log 2>&1 &
PF_AUTH=$!
sleep 0.4
kubectl "${PF_COMMON[@]}" svc/circleguard-identity-service 18081:8080 >/tmp/cg-pf-identity.log 2>&1 &
PF_ID=$!
sleep 0.4
kubectl "${PF_COMMON[@]}" svc/circleguard-form-service 18086:8080 >/tmp/cg-pf-form.log 2>&1 &
PF_FORM=$!
sleep 0.4
kubectl "${PF_COMMON[@]}" svc/circleguard-promotion-service 18088:8080 >/tmp/cg-pf-promotion.log 2>&1 &
PF_PROM=$!
sleep 0.4
kubectl "${PF_COMMON[@]}" svc/circleguard-gateway-service 18087:8080 >/tmp/cg-pf-gateway.log 2>&1 &
PF_GW=$!
sleep 2

wait_tcp() {
  local port="$1"
  local label="$2"
  local n=0
  while [[ "${n}" -lt 90 ]]; do
    if bash -c "echo >/dev/tcp/127.0.0.1/${port}" 2>/dev/null; then
      echo "[INFO] Port ${port} (${label}) is accepting connections."
      return 0
    fi
    sleep 1
    n=$((n + 1))
  done
  echo "[ERROR] Timeout waiting for port ${port} (${label}). Last kubectl port-forward log:"
  cat "/tmp/cg-pf-${label}.log" 2>/dev/null || true
  return 1
}

wait_tcp 18080 auth
wait_tcp 18081 identity
wait_tcp 18086 form
wait_tcp 18087 gateway
wait_tcp 18088 promotion

# TCP "open" only means the forwarder is listening; the pod may still be starting (promotion + Neo4j is often last).
http_code() {
  curl -s -o /dev/null -w "%{http_code}" --connect-timeout 3 --max-time 25 "$@" 2>/dev/null || echo "000"
}

echo "[INFO] HTTP warm-up through port-forwards (retries until backends respond)..."
warm_ok=0
for i in $(seq 1 90); do
  c_auth=$(http_code -X POST "http://127.0.0.1:18080/api/v1/auth/visitor/handoff" -H "Content-Type: application/json" -d "{\"anonymousId\":\"pf-warm-$i\"}")
  c_id=$(http_code -X POST "http://127.0.0.1:18081/api/v1/identities/map" -H "Content-Type: application/json" -d "{\"realIdentity\":\"pf-warm-$i\"}")
  c_form=$(http_code "http://127.0.0.1:18086/api/v1/questionnaires/active")
  c_prom=$(http_code "http://127.0.0.1:18088/api/v1/buildings")
  c_gw=$(http_code -X POST "http://127.0.0.1:18087/api/v1/gate/validate" -H "Content-Type: application/json" -d '{"token":"invalid.warm.token"}')
  if [[ "$c_auth" == "200" && "$c_id" == "200" && "$c_prom" == "200" && "$c_gw" != "000" ]]; then
    if [[ "$c_form" == "200" || "$c_form" == "404" ]]; then
      echo "[INFO] Warm-up OK (auth=$c_auth identity=$c_id form=$c_form promotion=$c_prom gateway=$c_gw) after ${i} attempt(s)."
      warm_ok=1
      break
    fi
  fi
  echo "[INFO] warm-up attempt $i: auth=$c_auth identity=$c_id form=$c_form promotion=$c_prom gateway=$c_gw"
  sleep 2
done
if [[ "$warm_ok" != "1" ]]; then
  echo "[ERROR] HTTP warm-up did not succeed. kubectl port-forward logs (promotion often fails last):"
  tail -n 80 /tmp/cg-pf-promotion.log 2>/dev/null || true
  exit 1
fi

export E2E_RUN=true
export E2E_AUTH_URL=http://127.0.0.1:18080
export E2E_GATEWAY_URL=http://127.0.0.1:18087
export E2E_FORM_URL=http://127.0.0.1:18086
export E2E_PROMOTION_URL=http://127.0.0.1:18088
export E2E_IDENTITY_URL=http://127.0.0.1:18081
export E2E_QR_SECRET="${E2E_QR_SECRET:-my-qr-secret-key-for-dev-1234567890}"

echo "[INFO] Running Gradle e2e-tests (E2E_* are set to loopback via port-forward)."
./gradlew :e2e-tests:test --build-cache
