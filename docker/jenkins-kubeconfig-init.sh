#!/usr/bin/env bash
# Waits for k3s to drop kubeconfig into /k3s-etc (shared volume), then writes ~/.kube/config for Jenkins.
set -euo pipefail

K3S_API_HOST="${K3S_API_HOST:-k3s}"
K3S_KUBECONFIG_SRC="${K3S_KUBECONFIG_SRC:-/k3s-etc/k3s.yaml}"
JENKINS_HOME="${JENKINS_HOME:-/var/jenkins_home}"

if [[ -d /k3s-etc ]]; then
  # Wait only for k3s.yaml — do not block on curl. The API may lag behind the file; kubectl uses certs from the file.
  echo "[jenkins-kubeconfig-init] Waiting for ${K3S_KUBECONFIG_SRC} (max ~3 min) ..."
  for i in $(seq 1 90); do
    if [[ -f "${K3S_KUBECONFIG_SRC}" ]]; then
      echo "[jenkins-kubeconfig-init] Found k3s.yaml after ${i} attempt(s)."
      break
    fi
    if (( i % 15 == 0 )); then
      echo "[jenkins-kubeconfig-init] still waiting for k3s.yaml… ${i}/90 (~$((i * 2))s)"
    fi
    sleep 2
  done

  if [[ -f "${K3S_KUBECONFIG_SRC}" ]]; then
    mkdir -p "${JENKINS_HOME}/.kube"
    sed \
      -e "s|https://127.0.0.1:6443|https://${K3S_API_HOST}:6443|g" \
      -e "s|https://localhost:6443|https://${K3S_API_HOST}:6443|g" \
      "${K3S_KUBECONFIG_SRC}" >"${JENKINS_HOME}/.kube/config"

    chown "${JENKINS_UID:-1000}:${JENKINS_GID:-1000}" "${JENKINS_HOME}/.kube/config"
    chmod 600 "${JENKINS_HOME}/.kube/config"
    mkdir -p /root/.kube
    cp -f "${JENKINS_HOME}/.kube/config" /root/.kube/config
    chmod 600 /root/.kube/config
    echo "[jenkins-kubeconfig-init] Wrote ${JENKINS_HOME}/.kube/config for https://${K3S_API_HOST}:6443"
    if curl -kfsS --connect-timeout 2 "https://${K3S_API_HOST}:6443/version" >/dev/null 2>&1; then
      echo "[jenkins-kubeconfig-init] HTTPS probe to API succeeded."
    else
      echo "[jenkins-kubeconfig-init] INFO: API not answering HTTPS probe yet (cluster may still be starting); kubectl should work once it is up."
    fi
  else
    echo "[jenkins-kubeconfig-init] WARN: ${K3S_KUBECONFIG_SRC} missing after wait; kubectl may not work."
  fi
else
  echo "[jenkins-kubeconfig-init] No /k3s-etc mount — skipping kubeconfig (run without bundled k3s)."
fi

exec /usr/bin/tini -g -- /usr/local/bin/jenkins.sh "$@"
