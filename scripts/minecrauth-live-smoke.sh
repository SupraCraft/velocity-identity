#!/usr/bin/env bash
set -euo pipefail

velocity_version="${1:-4.2.0}"
minecrauth_sha="${MINECRAUTH_SHA:-5b786bbd9cc7f8d0e892e63cec61543792455b19}"
port="${VIP_MINECRAUTH_PORT:-18082}"
root="$(pwd)"
work="$root/build/minecrauth-live-smoke"
src="$work/src"
state="$work/state"
config="$work/config.yaml"
log="$work/minecrauth.log"
vlog="$work/velocity.log"
run_dir="$root/run/velocity-$velocity_version"
plugin_dir="$run_dir/plugins/velocityidentity"
service="http://127.0.0.1:$port"
auth_root="$service/authlib-injector/authserver"
session_root="$service/authlib-injector/sessionserver"
has_joined="$session_root/session/minecraft/hasJoined"
email="vip-gha@example.com"
player="VipMinecrauth"
secret="$(python3 - <<'PY'
import secrets
print('Vip-' + secrets.token_hex(12))
PY
)"

rm -rf "$work" "$run_dir"
mkdir -p "$state" "$plugin_dir"

cleanup() {
  kill "${velocity_pid:-}" 2>/dev/null || true
  kill "${service_pid:-}" 2>/dev/null || true
}
trap cleanup EXIT

git init -q "$src"
git -C "$src" remote add origin https://github.com/achetronic/minecrauth.git
git -C "$src" fetch -q --depth=1 origin "$minecrauth_sha"
git -C "$src" checkout -q --detach FETCH_HEAD
test "$(git -C "$src" rev-parse HEAD)" = "$minecrauth_sha"
echo "minecrauth_sha=$minecrauth_sha"

( cd "$src" && CGO_ENABLED=0 go build -trimpath -o "$work/minecrauth" ./cmd/minecrauth )
echo "minecrauth_binary_sha256=$(sha256sum "$work/minecrauth" | awk '{print $1}')"

cat >"$config" <<EOF
base_url: "$service"
listen_addr: "127.0.0.1:$port"
state_dir: "$state"
server_name: "VIP GHA Minecrauth"
require_invite: false
max_players_per_user: 3
min_password_length: 8
token_expiry_seconds: 0
rate_limit_rps: 100
rate_limit_burst: 200
log_format: "text"
log_level: "info"
EOF

"$work/minecrauth" --config "$config" >"$log" 2>&1 &
service_pid=$!

for _ in $(seq 1 200); do
  curl -fsS "$service/readyz" >/dev/null 2>&1 && break
  kill -0 "$service_pid" 2>/dev/null || { cat "$log"; exit 1; }
  sleep 0.25
done
curl -fsS "$service/readyz" >/dev/null
echo "minecrauth_ready=PASS"

code=$(curl -sS -o "$work/register.json" -w '%{http_code}' -H 'Content-Type: application/json' -X POST "$service/api/register" --data "{\"email\":\"$email\",\"password\":\"$secret\"}")
test "$code" = 201
echo "minecrauth_register=PASS"

code=$(curl -sS -o "$work/player.json" -w '%{http_code}' -u "$email:$secret" -H 'Content-Type: application/json' -X POST "$service/api/me/players" --data "{\"name\":\"$player\"}")
test "$code" = 201
read -r uuid name < <(python3 - "$work/player.json" <<'PY'
import json,sys
x=json.load(open(sys.argv[1]))
print(x['uuid'],x['name'])
PY
)
test "$name" = "$player"
echo "minecrauth_player_create=PASS"
echo "minecrauth_player_uuid=$uuid"

mkdir -p "$work/node"
npm install --no-audit --no-fund --ignore-scripts --prefix "$work/node" minecraft-protocol@1.68.0 >/dev/null

cat >"$plugin_dir/velocity-identity.properties" <<'EOF'
default.class=ONLINE_SESSION
default.servers=*
EOF

JAVA_TOOL_OPTIONS="-Dmojang.sessionserver=$has_joined" timeout --signal=TERM --kill-after=10s 120s ./gradlew --no-daemon runVelocity -PvelocityVersion="$velocity_version" >"$vlog" 2>&1 &
velocity_pid=$!

for _ in $(seq 1 200); do
  grep -Fq 'VelocityIdentity reconciliation trigger=startup readiness=READY' "$vlog" 2>/dev/null && break
  kill -0 "$velocity_pid" 2>/dev/null || { cat "$vlog"; exit 1; }
  sleep 0.5
done

listen_port=$(grep -F 'Listening on ' "$vlog" | tail -n 1 | sed -E 's/.*:([0-9]+).*/\1/')
test -n "$listen_port"

NODE_PATH="$work/node/node_modules" node scripts/yggdrasil-live-client.js 127.0.0.1 "$listen_port" "$auth_root" "$session_root" "$email" "$secret" "$player" "$uuid" minecrauth
NODE_PATH="$work/node/node_modules" node scripts/yggdrasil-bad-password-client.js 127.0.0.1 "$listen_port" "$auth_root" "$session_root" "$email" invalid-credential minecrauth

python3 - "$plugin_dir/state/observation.json" "$service/authlib-injector" "$has_joined" <<'PY'
import json,sys
x=json.load(open(sys.argv[1]))
assert x['sessionAuthorityIssuer']==sys.argv[2], x
assert x['sessionHasJoinedEndpoint']==sys.argv[3], x
print('minecrauth_authority_observation=PASS')
PY

echo "minecrauth_live_online_session=PASS"
