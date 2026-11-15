#!/usr/bin/env bash
# Runs the relay on this machine for the Android app's tests.
#
# It is the same code and configuration as the deployed relay, with one
# difference: the two limits that count connections per IP address are raised,
# because every test phone connects from 127.0.0.1.
#
# Usage: scripts/test-relay.sh [port]     (run from backend/, after npm ci)
set -euo pipefail
cd "$(dirname "$0")/.."

awk '
  /^name = "(CONNECT|SIGNUP)_LIMIT"/ { raise = 1 }
  raise && /limit = / { sub(/limit = [0-9]+/, "limit = 100000"); raise = 0 }
  { print }
' wrangler.toml > wrangler.test.toml

if ! diff <(grep -c 'limit = 100000' wrangler.test.toml) <(echo 2) > /dev/null; then
  echo "test-relay.sh: expected to raise exactly two limits; wrangler.toml has changed shape" >&2
  exit 1
fi

exec npx wrangler dev --config wrangler.test.toml --ip 127.0.0.1 --port "${1:-8787}"
