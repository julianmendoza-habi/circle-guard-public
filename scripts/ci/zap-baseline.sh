#!/usr/bin/env bash
# OWASP ZAP baseline (passive) DAST scan against a RUNNING target (e.g. the gateway in dev).
#
# Usage:
#   scripts/ci/zap-baseline.sh TARGET_URL
#   scripts/ci/zap-baseline.sh http://127.0.0.1:18087
#
# Env knobs:
#   ZAP_FAIL_ON_FINDINGS  fail the build on ZAP FAIL-tagged alerts  (default: 0 = warn-only)
#   ZAP_RULES_FILE        rule tuning (IGNORE/WARN/FAIL)            (default: .zap/rules.tsv)
#   ZAP_REPORT_DIR        where reports are written                 (default: build/reports/zap)
#   ZAP_IMAGE             ZAP docker image                          (default: ghcr.io/zaproxy/zaproxy:stable)
#   ZAP_SPIDER_MINS       minutes to spider                         (default: 2)
#   ZAP_MAX_MINS          overall max minutes                       (default: 5)
#
# Runner: ZAP runs as a Docker container on the host network so it can reach a local kubectl
# port-forward (127.0.0.1:<port>). If Docker is unavailable the scan is SKIPPED (exit 0) so the
# pipeline degrades gracefully on bare agents — mirrors scripts/ci/trivy-scan.sh.
#
# zap-baseline.py is passive only (spider + passive rules); it does NOT attack the target, so it is
# safe to run against a shared dev environment. We pass -I so WARN alerts are reported but never set
# a failure code; ZAP_FAIL_ON_FINDINGS=1 then fails the build only on FAIL-tagged rules.
set -euo pipefail

TARGET="${1:-}"
if [ -z "${TARGET}" ]; then
  echo "[zap-baseline] no TARGET_URL passed; usage: $0 http://host:port" >&2
  exit 0
fi

FAIL_ON_FINDINGS="${ZAP_FAIL_ON_FINDINGS:-0}"
RULES_FILE="${ZAP_RULES_FILE:-.zap/rules.tsv}"
REPORT_DIR="${ZAP_REPORT_DIR:-build/reports/zap}"
ZAP_IMAGE="${ZAP_IMAGE:-ghcr.io/zaproxy/zaproxy:stable}"
SPIDER_MINS="${ZAP_SPIDER_MINS:-2}"
MAX_MINS="${ZAP_MAX_MINS:-5}"

if ! command -v docker >/dev/null 2>&1; then
  echo "[zap-baseline] WARNING: 'docker' not found — skipping DAST scan." >&2
  exit 0
fi

mkdir -p "${REPORT_DIR}"
ABS_REPORT_DIR="$(cd "${REPORT_DIR}" && pwd)"

# ZAP reads files relative to its /zap/wrk working dir; stage the rules file there if present.
ZAP_ARGS=(-t "${TARGET}" -r zap-report.html -J zap-report.json -w zap-report.md -I -m "${SPIDER_MINS}" -T "${MAX_MINS}")
if [ -f "${RULES_FILE}" ]; then
  cp "${RULES_FILE}" "${ABS_REPORT_DIR}/rules.tsv"
  ZAP_ARGS+=(-c rules.tsv)
fi

echo "==> OWASP ZAP baseline scan: ${TARGET} (fail-on-findings=${FAIL_ON_FINDINGS}, reports=${REPORT_DIR}/)"
set +e
docker run --rm --network host \
  -v "${ABS_REPORT_DIR}:/zap/wrk:rw" \
  "${ZAP_IMAGE}" zap-baseline.py "${ZAP_ARGS[@]}"
ZAP_RC=$?
set -e

echo "[zap-baseline] ZAP exit=${ZAP_RC} (0=clean, 1=FAIL alerts, 2=other, 3=error). Reports in ${REPORT_DIR}/"
if [ "${FAIL_ON_FINDINGS}" = "1" ] && [ "${ZAP_RC}" -ne 0 ]; then
  echo "[zap-baseline] failing build: ZAP reported FAIL-level alerts (ZAP_FAIL_ON_FINDINGS=1)." >&2
  exit "${ZAP_RC}"
fi
exit 0
