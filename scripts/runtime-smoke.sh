#!/usr/bin/env bash
set -euo pipefail

velocity_version="${1:-4.2.0}"
log_dir="build/runtime-smoke"
log_file="${log_dir}/velocity-${velocity_version}.log"
run_dir="run/velocity-${velocity_version}"

rm -rf "$run_dir" "$log_dir"
mkdir -p "$log_dir"

set +e
timeout --signal=TERM --kill-after=10s 45s   ./gradlew --no-daemon runVelocity -PvelocityVersion="$velocity_version"   >"$log_file" 2>&1
status=$?
set -e

# timeout(1) returns 124 when it terminates an otherwise healthy long-running server.
if [[ "$status" -ne 0 && "$status" -ne 124 && "$status" -ne 143 ]]; then
  cat "$log_file"
  echo "Velocity runtime exited unexpectedly: $status" >&2
  exit "$status"
fi

if ! grep -Fq "VelocityIdentity reconciliation readiness=READY" "$log_file"; then
  cat "$log_file"
  echo "VelocityIdentity did not reach independently verified READY state" >&2
  exit 1
fi

if grep -Fq "VelocityIdentity initialization failed" "$log_file"; then
  cat "$log_file"
  echo "VelocityIdentity reported an initialization failure" >&2
  exit 1
fi

echo "runtime_smoke=PASS"
echo "velocity_version=$velocity_version"
grep -F "VelocityIdentity reconciliation readiness=READY" "$log_file" | tail -n 1
