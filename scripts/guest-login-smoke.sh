#!/usr/bin/env bash
set -euo pipefail

velocity_version="${1:-4.2.0}"
run_dir="run/velocity-${velocity_version}"
log_dir="build/guest-login-smoke"
log_file="${log_dir}/velocity.log"
config_dir="${run_dir}/plugins/velocityidentity"

rm -rf "$run_dir" "$log_dir"
mkdir -p "$config_dir" "$log_dir"

cat >"${config_dir}/velocity-identity.properties" <<'EOF'
default.class=MICROSOFT
default.servers=*
host.127.0.0.1.class=GUEST
host.127.0.0.1.servers=*
EOF

timeout --signal=TERM --kill-after=10s 90s   ./gradlew --no-daemon runVelocity -PvelocityVersion="$velocity_version"   >"$log_file" 2>&1 &
runner=$!

cleanup() {
  kill "$runner" 2>/dev/null || true
  wait "$runner" 2>/dev/null || true
}
trap cleanup EXIT

ready=0
for _ in $(seq 1 160); do
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
  echo "VelocityIdentity did not reach READY for guest login smoke" >&2
  exit 1
fi

listening=0
for _ in $(seq 1 80); do
  if (exec 3<>/dev/tcp/127.0.0.1/25577) 2>/dev/null; then
    exec 3>&-
    exec 3<&-
    listening=1
    break
  fi
  if ! kill -0 "$runner" 2>/dev/null; then
    break
  fi
  sleep 0.25
done

if [[ "$listening" -ne 1 ]]; then
  cat "$log_file"
  echo "Velocity never opened its configured listening socket" >&2
  exit 1
fi

if ! python3 scripts/guest-login-probe.py 127.0.0.1 25577 769 ClientClaim; then
  cat "$log_file"
  exit 1
fi
