#!/usr/bin/env bash
# Scan one or more (locally built) container images for vulnerabilities with Trivy.
#
# Usage:
#   scripts/ci/trivy-scan.sh IMAGE [IMAGE ...]
#
# Env knobs:
#   TRIVY_SEVERITY        severities that count       (default: HIGH,CRITICAL)
#   TRIVY_EXIT_CODE       exit code on findings        (default: 1; set 0 for warn-only)
#   TRIVY_IGNORE_UNFIXED  skip CVEs with no fix yet     (default: true)
#   TRIVY_VERSION         image tag when running via Docker (default: 0.55.0)
#
# Runner: uses a host `trivy` binary if present; otherwise runs the official Trivy image via Docker
# (mounting the Docker socket so it can read locally built images). If neither is available the
# scan is SKIPPED with a warning (exit 0) so the pipeline degrades gracefully on bare agents.
set -euo pipefail

if [ "$#" -eq 0 ]; then
  echo "[trivy-scan] no images passed; nothing to scan" >&2
  exit 0
fi

SEVERITY="${TRIVY_SEVERITY:-HIGH,CRITICAL}"
EXIT_CODE="${TRIVY_EXIT_CODE:-1}"
IGNORE_UNFIXED="${TRIVY_IGNORE_UNFIXED:-true}"
TRIVY_VERSION="${TRIVY_VERSION:-0.55.0}"

UNFIXED_FLAG=""
[ "${IGNORE_UNFIXED}" = "true" ] && UNFIXED_FLAG="--ignore-unfixed"

run_trivy() {
  # $@ = trivy args
  if command -v trivy >/dev/null 2>&1; then
    trivy "$@"
  elif command -v docker >/dev/null 2>&1; then
    docker run --rm \
      -v /var/run/docker.sock:/var/run/docker.sock \
      -v "${HOME}/.cache/trivy:/root/.cache/trivy" \
      "aquasec/trivy:${TRIVY_VERSION}" "$@"
  else
    return 127
  fi
}

# Probe availability once.
if ! command -v trivy >/dev/null 2>&1 && ! command -v docker >/dev/null 2>&1; then
  echo "[trivy-scan] WARNING: neither 'trivy' nor 'docker' found — skipping image scan." >&2
  exit 0
fi

RC=0
for img in "$@"; do
  echo "==> Trivy scan: ${img} (severity=${SEVERITY}, ignore-unfixed=${IGNORE_UNFIXED})"
  if ! run_trivy image \
        --scanners vuln \
        --severity "${SEVERITY}" \
        ${UNFIXED_FLAG} \
        --exit-code "${EXIT_CODE}" \
        --no-progress \
        "${img}"; then
    echo "[trivy-scan] findings at/above ${SEVERITY} in ${img}" >&2
    RC="${EXIT_CODE}"
  fi
done

exit "${RC}"
