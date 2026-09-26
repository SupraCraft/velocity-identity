#!/usr/bin/env bash
set -euo pipefail

velocity_version="${1:-4.2.0}"
mcp_version="${MCP_VERSION:-1.68.0}"
oracle_port="${VIP_ORACLE_PORT:-18080}"
oracle_root="http://127.0.0.1:${oracle_port}"
has_joined="${oracle_root}/session/minecraft/hasJoined"

work="build/yggdrasil-session-smoke"
node_dir="${work}/node"
oracle_log="${work}/oracle.log"
velocity_log="${work}/velocity.log"
run_dir="run/velocity-${velocity_version}"
config_dir="${run_dir}/plugins/velocityidentity"
observation="${config_dir}/state/observation.json"

rm -rf "${work}" "${run_dir}"
mkdir -p "${work}" "${node_dir}" "${config_dir}"

cat >"${config_dir}/velocity-identity.properties" <<'EOF'
default.class=ONLINE_SESSION
default.servers=*
EOF

npm install --no-audit --no-fund --ignore-scripts   --prefix "${node_dir}" "minecraft-protocol@${mcp_version}" >/dev/null
resolved_mcp="$(NODE_PATH="${node_dir}/node_modules" node -p "require('minecraft-protocol/package.json').version")"
test "${resolved_mcp}" = "${mcp_version}"
echo "minecraft_protocol_version=${resolved_mcp}"
echo "node_version=$(node --version)"

node scripts/yggdrasil-session-oracle.js "${oracle_port}" >"${oracle_log}" 2>&1 &
oracle_pid=$!

cleanup() {
  if [[ -n "${velocity_pid:-}" ]]; then
    kill "${velocity_pid}" 2>/dev/null || true
    wait "${velocity_pid}" 2>/dev/null || true
  fi
  if [[ -n "${oracle_pid:-}" ]]; then
    kill "${oracle_pid}" 2>/dev/null || true
    wait "${oracle_pid}" 2>/dev/null || true
  fi
}
trap cleanup EXIT

oracle_ready=0
for _ in $(seq 1 80); do
  if grep -Fq "oracle_ready=PASS" "${oracle_log}" 2>/dev/null; then
    oracle_ready=1
    break
  fi
  if ! kill -0 "${oracle_pid}" 2>/dev/null; then
    break
  fi
  sleep 0.1
done
if [[ "${oracle_ready}" -ne 1 ]]; then
  cat "${oracle_log}" || true
  echo "Yggdrasil session oracle did not become ready" >&2
  exit 1
fi

echo "session_authority=${has_joined}"

JAVA_TOOL_OPTIONS="-Dmojang.sessionserver=${has_joined}"   timeout --signal=TERM --kill-after=10s 90s   ./gradlew --no-daemon runVelocity -PvelocityVersion="${velocity_version}"   >"${velocity_log}" 2>&1 &
velocity_pid=$!

ready=0
for _ in $(seq 1 160); do
  if grep -Fq "VelocityIdentity reconciliation trigger=startup readiness=READY" "${velocity_log}" 2>/dev/null; then
    ready=1
    break
  fi
  if ! kill -0 "${velocity_pid}" 2>/dev/null; then
    break
  fi
  sleep 0.5
done
if [[ "${ready}" -ne 1 ]]; then
  cat "${velocity_log}" || true
  echo "VIP did not reach READY with custom session authority" >&2
  exit 1
fi

if ! grep -Fq "VIP online session authority issuer=${oracle_root} hasJoined=${has_joined}" "${velocity_log}"; then
  cat "${velocity_log}"
  echo "VIP did not observe the expected custom session authority" >&2
  exit 1
fi

listen_port=""
for _ in $(seq 1 80); do
  listen_line="$(grep -F 'Listening on ' "${velocity_log}" 2>/dev/null | tail -n 1 || true)"
  if [[ -n "${listen_line}" ]]; then
    listen_port="$(printf '%s\n' "${listen_line}" | sed -E 's/.*:([0-9]+).*/\1/')"
    if [[ "${listen_port}" =~ ^[0-9]+$ ]]; then
      break
    fi
    listen_port=""
  fi
  sleep 0.25
done
if [[ -z "${listen_port}" ]]; then
  cat "${velocity_log}"
  echo "Velocity did not report a listening port" >&2
  exit 1
fi

echo "observed_velocity_port=${listen_port}"

NODE_PATH="${node_dir}/node_modules"   node scripts/yggdrasil-online-client.js   127.0.0.1 "${listen_port}" "${oracle_root}"

if ! grep -Fq "join=PASS" "${oracle_log}"     || ! grep -Fq "has_joined=PASS" "${oracle_log}"; then
  cat "${oracle_log}"
  echo "Oracle did not observe both join and hasJoined" >&2
  exit 1
fi

python3 - "${observation}" "${oracle_root}" "${has_joined}" <<'PY'
import json
import sys

path, issuer, endpoint = sys.argv[1:]
with open(path, encoding="utf-8") as handle:
    observed = json.load(handle)

assert observed["sessionAuthorityIssuer"] == issuer, observed
assert observed["sessionHasJoinedEndpoint"] == endpoint, observed
print("session_authority_observation=PASS")
print(f"observed_issuer={observed['sessionAuthorityIssuer']}")
print(f"observed_has_joined={observed['sessionHasJoinedEndpoint']}")
PY

echo "yggdrasil_online_session=PASS"
grep -E '^(oracle_ready|join|has_joined)=' "${oracle_log}"
