#!/usr/bin/env bash
#
# build.sh — build Diakrisis the shared-library way.
#
# Every module is a self-contained Maven project. The two libraries (common, engine) are
# installed to the local .m2 as versioned artifacts; the three deployables
# (decision-service, bank-app, etl) then build ON THEIR OWN, resolving common/engine from .m2.
#
# This script enforces the documented build order:
#   1. install libs to .m2 (common, then engine)
#   2. package each service independently (a separate, standalone Maven build per service)
#
# Usage:
#   ./build.sh                 # install libs, then package every service standalone
#   ./build.sh --with-tests    # also run 'mvn test' in decision-service (needs DynamoDB Local)
#   ./build.sh --help
#
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WITH_TESTS=0

for arg in "$@"; do
  case "$arg" in
    --with-tests) WITH_TESTS=1 ;;
    -h|--help)
      grep -E '^#( |$)' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "Unknown option: $arg (try --help)" >&2
      exit 2
      ;;
  esac
done

run() {
  echo "==> ($1) mvn ${*:2}"
  ( cd "${ROOT_DIR}/$1" && mvn "${@:2}" )
}

echo "### Step 1/2 — install libraries to local .m2 (versioned: com.cy.diakritis:common / :engine)"
run common -q clean install
run engine -q clean install

echo "### Step 2/2 — build each service INDEPENDENTLY (standalone Maven build per service)"
run decision-service -q clean package
run bank-app         -q clean package
run etl              -q clean package

if [[ "${WITH_TESTS}" -eq 1 ]]; then
  echo "### Optional — golden-path tests (DynamoDB Local must be running on :8000)"
  run decision-service -q test
fi

echo "### DONE — libraries published to .m2; decision-service, bank-app, etl each packaged standalone."
