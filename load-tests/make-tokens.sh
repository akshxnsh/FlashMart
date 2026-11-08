#!/usr/bin/env bash
# Logs every PlaceOrders test buyer in, directly on Identity :8081 (never through
# the Gateway), and writes the access tokens the Gatling scenario feeds from:
#   load-tests/tokens/warmup.csv     one token per warm-up buyer
#   load-tests/tokens/buyers.csv     one token per measured buyer
#   load-tests/tokens/expires-at.txt epoch seconds when the EARLIEST token expires
# The tokens/ folder is git-ignored: the tokens are live credentials until they expire.
#
# Access tokens live 15 min (identity.jwt.access-ttl). The script prints how long
# the logins took and how much of that lifetime is left; the whole Gatling run
# must fit inside what is left. Run it right before the simulation.
#
# Needs: the buyers from create-users.sh, Identity running, python on the PATH.
# Optional: same variables as create-users.sh (BUYERS and WARMUP must match it,
# or at least not exceed it).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec python "$HERE/buyers.py" tokens "$HERE/tokens"
