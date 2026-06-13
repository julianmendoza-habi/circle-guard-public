#!/usr/bin/env bash
# Compute the next semantic version from Conventional Commits since the last vX.Y.Z tag.
#
#   feat!: / fix!: / "BREAKING CHANGE" in body  -> major
#   feat:                                       -> minor
#   fix: / perf: / refactor: / others           -> patch
#   (no commits since the last tag)             -> no bump (re-print current)
#
# Usage:
#   scripts/ci/semantic-version.sh            # prints the computed next version, e.g. 1.3.0
#   scripts/ci/semantic-version.sh --current  # prints the latest released version (or 0.0.0)
#   scripts/ci/semantic-version.sh --tag      # also creates an annotated git tag vX.Y.Z (no push)
#
# Notes: the printed value has NO leading "v"; tags use the "v" prefix. Safe to run without any
# existing tags (baseline 0.0.0). Does not push — the caller decides when/whether to push tags.
set -euo pipefail

PREFIX="v"
LAST_TAG="$(git describe --tags --match "${PREFIX}[0-9]*" --abbrev=0 2>/dev/null || true)"

if [ -n "${LAST_TAG}" ]; then
  CURRENT="${LAST_TAG#"${PREFIX}"}"
  RANGE="${LAST_TAG}..HEAD"
else
  CURRENT="0.0.0"
  RANGE="HEAD"
fi

# Split current into components (default missing parts to 0).
IFS='.' read -r MAJOR MINOR PATCH <<EOF
${CURRENT}
EOF
MAJOR="${MAJOR:-0}"; MINOR="${MINOR:-0}"; PATCH="${PATCH:-0}"

if [ "${1:-}" = "--current" ]; then
  echo "${CURRENT}"
  exit 0
fi

# Collect commit subjects (+ bodies) in range to classify the bump.
SUBJECTS="$(git log "${RANGE}" --pretty=format:'%s' 2>/dev/null || true)"
BODIES="$(git log "${RANGE}" --pretty=format:'%b' 2>/dev/null || true)"

BUMP="none"
if [ -n "${SUBJECTS}" ]; then
  BUMP="patch"
  if printf '%s\n' "${SUBJECTS}" | grep -qE '^[a-z]+(\([^)]+\))?: '; then :; fi
  if printf '%s\n' "${SUBJECTS}" | grep -qE '^feat(\([^)]+\))?: '; then BUMP="minor"; fi
  # Breaking change markers take precedence.
  if printf '%s\n' "${SUBJECTS}" | grep -qE '^[a-z]+(\([^)]+\))?!: ' \
     || printf '%s\n' "${BODIES}" | grep -qE 'BREAKING[ -]CHANGE'; then
    BUMP="major"
  fi
fi

case "${BUMP}" in
  major) MAJOR=$((MAJOR + 1)); MINOR=0; PATCH=0 ;;
  minor) MINOR=$((MINOR + 1)); PATCH=0 ;;
  patch) PATCH=$((PATCH + 1)) ;;
  none)  : ;; # keep current
esac

NEXT="${MAJOR}.${MINOR}.${PATCH}"
echo "${NEXT}"

if [ "${1:-}" = "--tag" ] && [ "${BUMP}" != "none" ]; then
  git tag -a "${PREFIX}${NEXT}" -m "Release ${PREFIX}${NEXT}" >&2
  echo "[semantic-version] created tag ${PREFIX}${NEXT} (not pushed)" >&2
fi
