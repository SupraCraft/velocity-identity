#!/usr/bin/env bash
set -euo pipefail

velocity_version="${1:-4.2.0}"
drasl_sha="${DRASL_SHA:-1eb9d46189ba365591b0586c79862223165813da}"
drasl_repo="${DRASL_REPO:-https://github.com/unmojang/drasl.git}"
mcp_version="${MCP_VERSION:-1.68.0}"
drasl_port="${VIP_DRASL_PORT:-18081}"

root="$(pwd)"
work="${root}/build/drasl-live-smoke"
node_dir="${work}/node"
drasl_src="${work}/drasl-src"
drasl_state="${work}/drasl-state"
drasl_config="${work}/drasl.toml"
drasl_log="${work}/drasl.log"
velocity_log="${work}/velocity.log"
create_response="${work}/create-user.json"
run_dir="${root}/run/velocity-${velocity_version}"
config_dir="${run_dir}/plugins/velocityidentity"
observation="${config_dir}/state/observation.json"
drasl_root="http://127.0.0.1:${drasl_port}"
drasl_has_joined="${drasl_root}/session/minecraft/hasJoined"
username="vip_gha_user"
player_name="VipGhaUser"
password="vip-live-password-123"

rm -rf "${work}" "${run_dir}"
mkdir -p "${work}" "${node_dir}" "${drasl_state}" "${config_dir}"

cleanup() {
  if [[ -n "${velocity_pid:-}" ]]; then
    kill "${velocity_pid}" 2>/dev/null || true
    wait "${velocity_pid}" 2>/dev/null || true
  fi
  if [[ -n "${drasl_pid:-}" ]]; then
    kill "${drasl_pid}" 2>/dev/null || true
    wait "${drasl_pid}" 2>/dev/null || true
  fi
}
trap cleanup EXIT

git init -q "${drasl_src}"
git -C "${drasl_src}" remote add origin "${drasl_repo}"
git -C "${drasl_src}" fetch -q --depth=1 origin "${drasl_sha}"
git -C "${drasl_src}" checkout -q --detach FETCH_HEAD
test "$(git -C "${drasl_src}" rev-parse HEAD)" = "${drasl_sha}"
echo "drasl_sha=${drasl_sha}"

(
  cd "${drasl_src}"
  echo "go_version=$(go version)"
  go build -trimpath -o "${work}/drasl" .
)
test -x "${work}/drasl"
echo "drasl_binary_sha256=$(sha256sum "${work}/drasl" | awk '{print $1}')"

cat >"${drasl_config}" <<EOF
Domain = "127.0.0.1"
BaseURL = "${drasl_root}"
ListenAddress = "127.0.0.1:${drasl_port}"
StateDirectory = "${drasl_state}"
DataDirectory = "${drasl_src}"
EnableWebFrontEnd = false
EnableBackgroundEffect = false
EnableFooter = false
LogRequests = true
SignPublicKeys = false
AllowPasswordLogin = true
PreMigrationBackups = false
PlayerUUIDGeneration = "random"

[RateLimit]
Enable = false
RequestsPerSecond = 2.0
Burst = 60

[RegistrationUsernamePassword.CreateNewPlayer]
Allow = true
RequireInvite = false
AllowChoosingUUID = false
EOF

"${work}/drasl" --config "${drasl_config}" >"${drasl_log}" 2>&1 &
drasl_pid=$!

drasl_ready=0
for _ in $(seq 1 200); do
  if curl -fsS "${drasl_root}/publickeys" >/dev/null 2>&1; then
    drasl_ready=1
    break
  fi
  if ! kill -0 "${drasl_pid}" 2>/dev/null; then
    break
  fi
  sleep 0.25
done
if [[ "${drasl_ready}" -ne 1 ]]; then
  cat "${drasl_log}" >&2
  echo "Drasl did not become ready" >&2
  exit 1
fi
echo "drasl_ready=PASS"

curl -fsS   -H 'Content-Type: application/json'   -X POST "${drasl_root}/drasl/api/v3/users"   --data-binary @- >"${create_response}" <<EOF
{
  "username": "${username}",
  "password": "${password}",
  "playerName": "${player_name}",
  "requestApiToken": true
}
EOF

read -r player_uuid created_name < <(
  python3 - "${create_response}" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as handle:
    obj = json.load(handle)
players = obj["user"]["players"]
assert len(players) == 1, obj
print(players[0]["uuid"], players[0]["name"])
PY
)

test "${created_name}" = "${player_name}"
echo "drasl_user_create=PASS"
echo "drasl_player_uuid=${player_uuid}"
echo "drasl_player_name=${created_name}"

curl -fsS   -H 'Content-Type: application/json'   -X POST "${drasl_root}/drasl/api/v3/login"   --data-binary @- >/dev/null <<EOF
{"username":"${username}","password":"${password}"}
EOF
echo "drasl_api_login=PASS"

npm install --no-audit --no-fund --ignore-scripts   --prefix "${node_dir}" "minecraft-protocol@${mcp_version}" >/dev/null
resolved_mcp="$(NODE_PATH="${node_dir}/node_modules" node -p "require('minecraft-protocol/package.json').version")"
test "${resolved_mcp}" = "${mcp_version}"
echo "minecraft_protocol_version=${resolved_mcp}"

cat >"${config_dir}/velocity-identity.properties" <<'EOF'
default.class=ONLINE_SESSION
default.servers=*
EOF

JAVA_TOOL_OPTIONS="-Dmojang.sessionserver=${drasl_has_joined}"   timeout --signal=TERM --kill-after=10s 120s   ./gradlew --no-daemon runVelocity -PvelocityVersion="${velocity_version}"   >"${velocity_log}" 2>&1 &
velocity_pid=$!

ready=0
for _ in $(seq 1 200); do
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
  cat "${velocity_log}" >&2
  echo "VIP did not reach READY against live Drasl" >&2
  exit 1
fi

if ! grep -Fq "VIP online session authority issuer=${drasl_root} hasJoined=${drasl_has_joined}" "${velocity_log}"; then
  cat "${velocity_log}" >&2
  echo "VIP did not observe live Drasl as its online-session authority" >&2
  exit 1
fi

listen_port=""
for _ in $(seq 1 100); do
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
  cat "${velocity_log}" >&2
  exit 1
fi
echo "observed_velocity_port=${listen_port}"

NODE_PATH="${node_dir}/node_modules"   node scripts/drasl-online-client.js   127.0.0.1 "${listen_port}" "${drasl_root}"   "${username}" "${password}" "${player_name}" "${player_uuid}"

NODE_PATH="${node_dir}/node_modules"   node scripts/drasl-bad-password-client.js   127.0.0.1 "${listen_port}" "${drasl_root}"   "${username}" "definitely-wrong-password"

python3 - "${observation}" "${drasl_root}" "${drasl_has_joined}" <<'PY'
import json
import sys

path, issuer, endpoint = sys.argv[1:]
with open(path, encoding="utf-8") as handle:
    observed = json.load(handle)

assert observed["sessionAuthorityIssuer"] == issuer, observed
assert observed["sessionHasJoinedEndpoint"] == endpoint, observed
print("drasl_authority_observation=PASS")
print(f"observed_issuer={observed['sessionAuthorityIssuer']}")
print(f"observed_has_joined={observed['sessionHasJoinedEndpoint']}")
PY

if ! grep -Fq "/authenticate" "${drasl_log}"     || ! grep -Fq "/session/minecraft/join" "${drasl_log}"     || ! grep -Fq "/session/minecraft/hasJoined" "${drasl_log}"; then
  cat "${drasl_log}" >&2
  echo "Live Drasl log did not show full authenticate -> join -> hasJoined chain" >&2
  exit 1
fi

echo "drasl_live_online_session=PASS"
grep -E 'authenticate|session/minecraft/(join|hasJoined)' "${drasl_log}" | tail -n 20 || true
