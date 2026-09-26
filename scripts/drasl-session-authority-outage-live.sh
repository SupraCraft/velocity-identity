#!/usr/bin/env bash
set -euo pipefail

VELOCITY_VERSION="${VELOCITY_VERSION:-4.2.0}"
DRASL_SHA="${DRASL_SHA:-1eb9d46189ba365591b0586c79862223165813da}"
MCP_VERSION="${MCP_VERSION:-1.68.0}"

root="$(pwd)"
work="$root/build/drasl-session-authority-outage"
drasl_src="$work/drasl-src"
drasl_state="$work/drasl-state"
drasl_log="$work/drasl.log"
velocity_log="$work/velocity.log"
velocity_run="$root/run/velocity-$VELOCITY_VERSION"
plugin_dir="$velocity_run/plugins/velocityidentity"
drasl_root="http://127.0.0.1:18081"
dead_has_joined="http://127.0.0.1:18083/session/minecraft/hasJoined"
username='vip_outage_user'
password='vip-outage-password-123'
player_name='VipOutage'

rm -rf "$work" "$velocity_run"
mkdir -p "$work" "$drasl_state" "$plugin_dir"

cleanup() {
  kill "${velocity_pid:-}" 2>/dev/null || true
  kill "${drasl_pid:-}" 2>/dev/null || true
}
trap cleanup EXIT

git init -q "$drasl_src"
git -C "$drasl_src" remote add origin https://github.com/unmojang/drasl.git
git -C "$drasl_src" fetch -q --depth=1 origin "$DRASL_SHA"
git -C "$drasl_src" checkout -q --detach FETCH_HEAD
test "$(git -C "$drasl_src" rev-parse HEAD)" = "$DRASL_SHA"
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
EOF

"$work/drasl" --config "$work/drasl.toml" >"$drasl_log" 2>&1 &
drasl_pid=$!
for _ in $(seq 1 160); do
  curl -fsS "$drasl_root/publickeys" >/dev/null 2>&1 && break
  kill -0 "$drasl_pid" 2>/dev/null || { cat "$drasl_log"; exit 1; }
  sleep 0.25
done
curl -fsS "$drasl_root/publickeys" >/dev/null

curl -fsS -H 'Content-Type: application/json' -X POST "$drasl_root/drasl/api/v3/users" --data-binary @- >"$work/user.json" <<EOF
{"username":"$username","password":"$password","playerName":"$player_name","requestApiToken":true}
EOF

mkdir -p "$work/node"
npm install --no-audit --no-fund --ignore-scripts --prefix "$work/node" "minecraft-protocol@$MCP_VERSION" >/dev/null

cat >"$plugin_dir/velocity-identity.properties" <<'EOF'
default.class=ONLINE_SESSION
default.servers=*
EOF

JAVA_TOOL_OPTIONS="-Dmojang.sessionserver=$dead_has_joined"   timeout --signal=TERM --kill-after=10s 120s   ./gradlew --no-daemon runVelocity -PvelocityVersion="$VELOCITY_VERSION"   >"$velocity_log" 2>&1 &
velocity_pid=$!

for _ in $(seq 1 180); do
  grep -Fq "VelocityIdentity reconciliation trigger=startup readiness=READY" "$velocity_log" 2>/dev/null     && grep -Fq "Listening on " "$velocity_log" 2>/dev/null && break
  kill -0 "$velocity_pid" 2>/dev/null || { cat "$velocity_log"; exit 1; }
  sleep 0.5
done

listen_port=$(grep -F 'Listening on ' "$velocity_log" | tail -n1 | sed -E 's/.*:([0-9]+).*/\1/')
test -n "$listen_port"

NODE_PATH="$work/node/node_modules"   node scripts/yggdrasil-expected-login-failure.js   127.0.0.1 "$listen_port" "$drasl_root" "$drasl_root"   "$username" "$password" session-authority-outage

if ! grep -Fq '"uri":"/session/minecraft/join","status":204' "$drasl_log"; then
  cat "$drasl_log"
  echo "real Drasl join did not complete before Velocity verification failure" >&2
  exit 1
fi

if grep -Eq 'Guest_[0-9a-fA-F]{10}' "$velocity_log"; then
  cat "$velocity_log"
  echo "session authority outage downgraded into guest identity" >&2
  exit 1
fi

echo "real_drasl_join=PASS"
echo "unavailable_velocity_has_joined=PASS"
echo "no_guest_downgrade=PASS"
echo "session_authority_outage_fail_closed=PASS"
