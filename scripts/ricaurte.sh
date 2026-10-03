#!/usr/bin/env bash
# S43 and S48 inbound to Ricaurte NQS (TM0069).
# Extra args go to eta.py, e.g. scripts/ricaurte.sh -n 6
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
"$root/scripts/eta.py" S43 TM0069 "$@"
echo
echo '========'
echo
"$root/scripts/eta.py" S48 TM0069 "$@"
