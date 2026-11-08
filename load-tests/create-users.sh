#!/usr/bin/env bash
# Creates the PlaceOrders test buyers by real signups on Identity (real bcrypt),
# directly on :8081, never through the Gateway (its auth limiter is 1/s per IP).
#
# Count = BUYERS measured + WARMUP warm-up buyers (default 5000 + 200).
# Idempotent: a buyer that already exists (409, email taken) is skipped, so a
# second run creates nothing. Emails and the shared password are TEST-ONLY and
# fixed (see buyers.py). Exits non-zero if any signup is neither 201 nor 409.
#
# Needs: Identity running, python on the PATH.
# Optional: IDENTITY_URL (default http://localhost:8081), BUYERS, WARMUP,
#           CONCURRENCY (parallel signups; default = CPU count), HTTP_TIMEOUT_S (default 30).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec python "$HERE/buyers.py" create
