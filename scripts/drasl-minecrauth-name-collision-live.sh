#!/usr/bin/env bash
set -euo pipefail

VELOCITY_VERSION="${VELOCITY_VERSION:-4.2.0}"
DRASL_SHA="${DRASL_SHA:-1eb9d46189ba365591b0586c79862223165813da}"
MINECRAUTH_SHA="${MINECRAUTH_SHA:-5b786bbd9cc7f8d0e892e63cec61543792455b19}"
MCP_VERSION="${MCP_VERSION:-1.68.0}"

root="$(pwd)"
work="$root/build/cross-realm-name-collision"
drasl_src="$work/drasl-src"
mine_src="$work/mine-src"
drasl_state="$work/drasl-state"
mine_state="$work/mine-state"
drasl_log="$work/drasl.log"
velocity_log="$work/velocity.log"
velocity_run="$root/run/velocity-$VELOCITY_VERSION"
plugin_dir="$velocity_run/plugins/velocityidentity"
drasl_root="http://127.0.0.1:18081"
mine_root="http://127.0.0.1:18082"
mine_auth="$mine_root/authlib-injector/authserver"
mine_session="$mine_root/authlib-injector/sessionserver"
collision_name='VipCollision'
local_user='vip_col_local'
local_pass='vip-collision-local-123'
mine_email='vip-collision@example.com'
mine_pass='vip-collision-mine-123'

rm -rf "$work" "$velocity_run"
mkdir -p "$work" "$drasl_state" "$mine_state" "$plugin_dir"

cleanup() {
  kill "${velocity_pid:-}" 2>/dev/null || true
  kill "${drasl_pid:-}" 2>/dev/null || true
  kill "${mine_pid:-}" 2>/dev/null || true
}
trap cleanup EXIT

checkout() {
  local repo="$1" sha="$2" dest="$3"
  git init -q "$dest"
  git -C "$dest" remote add origin "$repo"
  git -C "$dest" fetch -q --depth=1 origin "$sha"
  git -C "$dest" checkout -q --detach FETCH_HEAD
  test "$(git -C "$dest" rev-parse HEAD)" = "$sha"
}

checkout https://github.com/achetronic/minecrauth.git "$MINECRAUTH_SHA" "$mine_src"
( cd "$mine_src" && CGO_ENABLED=0 go build -trimpath -o "$work/minecrauth" ./cmd/minecrauth )
cat >"$work/mine.yaml" <<EOF
base_url: "$mine_root"
listen_addr: "127.0.0.1:18082"
state_dir: "$mine_state"
server_name: "VIP collision Minecrauth"
require_invite: false
max_players_per_user: 3
min_password_length: 8
rate_limit_rps: 100
rate_limit_burst: 200
log_format: "text"
log_level: "info"
EOF
"$work/minecrauth" --config "$work/mine.yaml" >"$work/mine.log" 2>&1 &
mine_pid=$!
for _ in $(seq 1 160); do curl -fsS "$mine_root/readyz" >/dev/null 2>&1 && break; sleep 0.25; done
curl -fsS "$mine_root/readyz" >/dev/null

checkout https://github.com/unmojang/drasl.git "$DRASL_SHA" "$drasl_src"
( cd "$drasl_src" && go build -trimpath -o "$work/drasl" . )
cat >"$work/drasl.toml" <<EOF
Domain = "127.0.0.1"
BaseURL = "$drasl_root"
ListenAddress = "127.0.0.1:18081"
StateDirectory = "$drasl_state"
DataDirectory = "$drasl_src"
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

[[FallbackAPIServers]]
Nickname = "Minecrauth"
AuthlibInjectorURL = "$mine_root/authlib-injector"
CacheTTLSeconds = 0
DenyUnknownUsers = false
EnableAuthentication = true
ForwardSkins = true
EOF
"$work/drasl" --config "$work/drasl.toml" >"$drasl_log" 2>&1 &
drasl_pid=$!
for _ in $(seq 1 160); do curl -fsS "$drasl_root/publickeys" >/dev/null 2>&1 && break; sleep 0.25; done
curl -fsS "$drasl_root/publickeys" >/dev/null

curl -fsS -H 'Content-Type: application/json' -X POST "$drasl_root/drasl/api/v3/users" --data-binary @- >"$work/local.json" <<EOF
{"username":"$local_user","password":"$local_pass","playerName":"$collision_name","requestApiToken":true}
EOF
local_uuid=$(python3 - "$work/local.json" <<'PY'
import json,sys
print(json.load(open(sys.argv[1]))["user"]["players"][0]["uuid"])
PY
)

# Create the fallback-realm identity only after the local Drasl identity exists.
# This exercises a real post-registration collision that Drasl cannot preemptively
# reject at local account creation time.
test "$(curl -sS -o "$work/mine-register.json" -w '%{http_code}' -H 'Content-Type: application/json' -X POST "$mine_root/api/register" --data-binary @- <<EOF
{"email":"$mine_email","password":"$mine_pass"}
EOF
)" = 201
test "$(curl -sS -o "$work/mine-player.json" -w '%{http_code}' -u "$mine_email:$mine_pass" -H 'Content-Type: application/json' -X POST "$mine_root/api/me/players" --data-binary @- <<EOF
{"name":"$collision_name"}
EOF
)" = 201
mine_uuid=$(python3 - "$work/mine-player.json" <<'PY'
import json,sys
print(json.load(open(sys.argv[1]))["uuid"])
PY
)

test "$local_uuid" != "$mine_uuid"
echo "collision_name=$collision_name"
echo "drasl_uuid=$local_uuid"
echo "minecrauth_uuid=$mine_uuid"

mkdir -p "$work/node"
npm install --no-audit --no-fund --ignore-scripts --prefix "$work/node" "minecraft-protocol@$MCP_VERSION" >/dev/null

cat >"$plugin_dir/velocity-identity.properties" <<'EOF'
default.class=ONLINE_SESSION
default.servers=*
EOF

JAVA_TOOL_OPTIONS="-Dmojang.sessionserver=$drasl_root/session/minecraft/hasJoined"   timeout --signal=TERM --kill-after=10s 120s   ./gradlew --no-daemon runVelocity -PvelocityVersion="$VELOCITY_VERSION"   >"$velocity_log" 2>&1 &
velocity_pid=$!

for _ in $(seq 1 180); do
  grep -Fq "VelocityIdentity reconciliation trigger=startup readiness=READY" "$velocity_log" 2>/dev/null     && grep -Fq "Listening on " "$velocity_log" 2>/dev/null && break
  sleep 0.5
done
listen_port=$(grep -F 'Listening on ' "$velocity_log" | tail -n1 | sed -E 's/.*:([0-9]+).*/\1/')
test -n "$listen_port"

NODE_PATH="$work/node/node_modules" node scripts/yggdrasil-live-client.js   127.0.0.1 "$listen_port" "$drasl_root" "$drasl_root"   "$local_user" "$local_pass" "$collision_name" "$local_uuid" collision-local

NODE_PATH="$work/node/node_modules" node scripts/yggdrasil-live-client.js   127.0.0.1 "$listen_port" "$mine_auth" "$mine_session"   "$mine_email" "$mine_pass" "$collision_name" "$mine_uuid" collision-fallback

echo "cross_realm_same_name_distinct_uuid=PASS"
echo "name_is_not_identity_key=PASS"
