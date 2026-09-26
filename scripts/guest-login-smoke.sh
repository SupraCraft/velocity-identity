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

listen_port=""
for _ in $(seq 1 80); do
  listen_line="$(grep -E 'Listening on .+:[0-9]+ "$log_file" 2>/dev/null | tail -n 1 || true)"
  if [[ -n "$listen_line" ]]; then
    listen_port="$(printf '%s\n' "$listen_line" | sed -E 's/.*:([0-9]+)$/\1/')"
    break
  fi
  if ! kill -0 "$runner" 2>/dev/null; then
    break
  fi
  sleep 0.25
done

if [[ -z "$listen_port" ]]; then
  cat "$log_file"
  echo "Velocity did not report a bound listening endpoint" >&2
  exit 1
fi

socket_ready=0
for _ in $(seq 1 40); do
  if (exec 3<>"/dev/tcp/127.0.0.1/$listen_port") 2>/dev/null; then
    exec 3>&-
    exec 3<&-
    socket_ready=1
    break
  fi
  sleep 0.1
done

if [[ "$socket_ready" -ne 1 ]]; then
  cat "$log_file"
  echo "Velocity reported port $listen_port but the TCP socket was not reachable" >&2
  exit 1
fi

echo "observed_velocity_port=$listen_port"

if ! python3 scripts/guest-login-probe.py 127.0.0.1 "$listen_port" 769 ClientClaim; then
  cat "$log_file"
  exit 1
fi
