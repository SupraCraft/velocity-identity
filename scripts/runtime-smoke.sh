#!/usr/bin/env bash
set -euo pipefail

velocity_version="${1:-4.2.0}"
log_dir="build/runtime-smoke"
log_file="${log_dir}/velocity-${velocity_version}.log"
run_dir="run/velocity-${velocity_version}"

rm -rf "$run_dir" "$log_dir"
mkdir -p "$log_dir"

timeout --signal=TERM --kill-after=10s 60s   ./gradlew --no-daemon runVelocity -PvelocityVersion="$velocity_version"   >"$log_file" 2>&1 &
runner=$!

cleanup() {
  kill "$runner" 2>/dev/null || true
  wait "$runner" 2>/dev/null || true
}
trap cleanup EXIT

ready=0
for _ in $(seq 1 120); do
  if grep -Fq "VelocityIdentity reconciliation readiness=READY" "$log_file" 2>/dev/null; then
    ready=1
    break
  fi
  if ! kill -0 "$runner" 2>/dev/null; then
    break
  fi
  sleep 0.5
done

if [[ "$ready" -ne 1 ]]; then
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
