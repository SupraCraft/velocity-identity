#!/usr/bin/env bash
set -euo pipefail

VELOCITY_VERSION="${VELOCITY_VERSION:-4.2.0}"
MINECRAFT_VERSION="${MINECRAFT_VERSION:-1.21.4}"
DRASL_SHA="${DRASL_SHA:-1eb9d46189ba365591b0586c79862223165813da}"
BRIDGE_REPO="${BRIDGE_REPO:-https://github.com/SupraCraft/Bridge.git}"
BRIDGE_SHA="${BRIDGE_SHA:-5c07448179761d330957d086ab2c2f84793f9528}"
BRIDGE_VERSION="${BRIDGE_VERSION:-0.1.2-dev}"
VANILLACORD_REPO="${VANILLACORD_REPO:-https://github.com/SupraCraft/VanillaCord.git}"
VANILLACORD_SHA="${VANILLACORD_SHA:-ae95c0e64c4b867a60909b71bd2eb8d17051a5e5}"
MCP_VERSION="${MCP_VERSION:-1.68.0}"
DRASL_PORT="${VIP_DRASL_PORT:-18081}"

root="$(pwd)"
work="$root/build/drasl-vanillacord-live"
backend="$work/backend"
node_dir="$work/node"
drasl_src="$work/drasl-src"
drasl_state="$work/drasl-state"
drasl_config="$work/drasl.toml"
drasl_log="$work/drasl.log"
backend_log="$work/backend.log"
velocity_log="$work/velocity.log"
identity_json="$work/identity.json"
velocity_run="$root/run/velocity-$VELOCITY_VERSION"
plugin_dir="$velocity_run/plugins/velocityidentity"
drasl_root="http://127.0.0.1:$DRASL_PORT"
has_joined="$drasl_root/session/minecraft/hasJoined"
secret='vip-live-provider-vanillacord-4c9b53d523'
username='vip_backend_user'
password='vip-backend-password-123'
player_name='VipBackend'

rm -rf "$work" "$velocity_run"
mkdir -p "$work" "$backend" "$node_dir" "$drasl_state" "$plugin_dir"

cleanup() {
  if [[ -n "${velocity_pid:-}" ]]; then
    kill "$velocity_pid" 2>/dev/null || true
    wait "$velocity_pid" 2>/dev/null || true
  fi
  if [[ -n "${backend_pid:-}" ]]; then
    printf 'stop\n' >&9 2>/dev/null || true
    sleep 1
    kill "$backend_pid" 2>/dev/null || true
    wait "$backend_pid" 2>/dev/null || true
    exec 9>&- 2>/dev/null || true
  fi
  if [[ -n "${drasl_pid:-}" ]]; then
    kill "$drasl_pid" 2>/dev/null || true
    wait "$drasl_pid" 2>/dev/null || true
  fi
}
trap cleanup EXIT

checkout_pinned() {
  local url="$1" sha="$2" dest="$3"
  git init -q "$dest"
  git -C "$dest" remote add origin "$url"
  git -C "$dest" fetch -q --depth=1 origin "$sha"
  git -C "$dest" checkout -q --detach FETCH_HEAD
  test "$(git -C "$dest" rev-parse HEAD)" = "$sha"
}

echo "drasl_sha=$DRASL_SHA"
echo "bridge_sha=$BRIDGE_SHA"
echo "vanillacord_sha=$VANILLACORD_SHA"

checkout_pinned https://github.com/unmojang/drasl.git "$DRASL_SHA" "$drasl_src"
(
  cd "$drasl_src"
  go build -trimpath -o "$work/drasl" .
)
echo "drasl_binary_sha256=$(sha256sum "$work/drasl" | awk '{print $1}')"

cat >"$drasl_config" <<EOF
Domain = "127.0.0.1"
BaseURL = "$drasl_root"
ListenAddress = "127.0.0.1:$DRASL_PORT"
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

"$work/drasl" --config "$drasl_config" >"$drasl_log" 2>&1 &
drasl_pid=$!
for _ in $(seq 1 160); do
  curl -fsS "$drasl_root/publickeys" >/dev/null 2>&1 && break
  kill -0 "$drasl_pid" 2>/dev/null || { cat "$drasl_log"; exit 1; }
  sleep 0.25
done
curl -fsS "$drasl_root/publickeys" >/dev/null

curl -fsS -H 'Content-Type: application/json' -X POST "$drasl_root/drasl/api/v3/users"   --data-binary @- >"$work/user.json" <<EOF
{"username":"$username","password":"$password","playerName":"$player_name","requestApiToken":true}
EOF
read -r player_uuid created_name < <(
  python3 - "$work/user.json" <<'PY'
import json,sys
x=json.load(open(sys.argv[1]))
p=x["user"]["players"][0]
print(p["uuid"],p["name"])
PY
)
test "$created_name" = "$player_name"
echo "drasl_player_uuid=$player_uuid"

checkout_pinned "$BRIDGE_REPO" "$BRIDGE_SHA" "$work/Bridge"
checkout_pinned "$VANILLACORD_REPO" "$VANILLACORD_SHA" "$work/VanillaCord"
shared_maven_repo="$work/VanillaCord/.m2/repository"
mkdir -p "$shared_maven_repo"

(
  cd "$work/Bridge"
  chmod +x mvnw
  ./mvnw -B -DskipTests -Dmaven.repo.local="$shared_maven_repo" install
)
(
  cd "$work/VanillaCord"
  chmod +x mvnw
  ./mvnw -B -DskipTests package -Dmaven.repo.local="$shared_maven_repo"     -Dbridge.owner=SupraCraft -Dbridge.version="$BRIDGE_VERSION"
)
mapfile -t jars < <(find "$work/VanillaCord/artifacts" -maxdepth 1 -type f -name 'supracraft-vanillacord-*.jar' -print | LC_ALL=C sort)
test "${#jars[@]}" -eq 1
java -jar "${jars[0]}" "$MINECRAFT_VERSION"
cp "$work/VanillaCord/out/$MINECRAFT_VERSION.jar" "$backend/server.jar"

cat >"$backend/eula.txt" <<'EOF'
eula=true
EOF
cat >"$backend/server.properties" <<'EOF'
online-mode=false
enforce-secure-profile=false
server-ip=127.0.0.1
server-port=25566
network-compression-threshold=-1
motd=VIP Drasl VanillaCord live integration
level-name=world
view-distance=2
simulation-distance=2
spawn-protection=0
enable-status=false
EOF
cat >"$backend/vanillacord.txt" <<EOF
version = 2.0
forwarding = velocity
seecret = $secret
EOF

mkfifo "$work/backend.in"
exec 9<>"$work/backend.in"
(
  cd "$backend"
  java -Xms256M -Xmx1536M -jar server.jar nogui <&9 >"$backend_log" 2>&1
) &
backend_pid=$!

backend_ready=0
for _ in $(seq 1 140); do
  if grep -Eq 'Done \([^)]*\)!|Done \(' "$backend_log" 2>/dev/null; then backend_ready=1; break; fi
  kill -0 "$backend_pid" 2>/dev/null || break
  sleep 1
done
if [[ "$backend_ready" -ne 1 ]]; then cat "$backend_log"; exit 1; fi

cat >"$velocity_run/velocity.toml" <<'EOF'
config-version = "2.9"
bind = "127.0.0.1:25577"
motd = "<green>VIP live provider integration"
show-max-players = 10
online-mode = true
force-key-authentication = false
prevent-client-proxy-connections = false
player-info-forwarding-mode = "modern"
forwarding-secret-file = "forwarding.secret"
kick-existing-players = false
sample-players-in-ping = false
enable-player-address-logging = true

[servers]
backend = "127.0.0.1:25566"
try = ["backend"]

[forced-hosts]

[advanced]
compression-threshold = 256
compression-level = -1
login-ratelimit = 0
connection-timeout = 5000
read-timeout = 30000
failover-on-unexpected-server-disconnect = false
accepts-transfers = false

[query]
enabled = false
EOF
printf '%s\n' "$secret" >"$velocity_run/forwarding.secret"
cat >"$plugin_dir/velocity-identity.properties" <<'EOF'
default.class=ONLINE_SESSION
default.servers=backend
EOF

JAVA_TOOL_OPTIONS="-Dmojang.sessionserver=$has_joined"   timeout --signal=TERM --kill-after=10s 150s ./gradlew --no-daemon runVelocity   -PvelocityVersion="$VELOCITY_VERSION" >"$velocity_log" 2>&1 &
velocity_pid=$!

for _ in $(seq 1 200); do
  grep -Fq "VelocityIdentity reconciliation trigger=startup readiness=READY" "$velocity_log" 2>/dev/null     && grep -Fq "Listening on " "$velocity_log" 2>/dev/null && break
  kill -0 "$velocity_pid" 2>/dev/null || { cat "$velocity_log"; exit 1; }
  sleep 0.5
done

npm install --no-audit --no-fund --ignore-scripts --prefix "$node_dir" "minecraft-protocol@$MCP_VERSION" >/dev/null
NODE_PATH="$node_dir/node_modules" node scripts/yggdrasil-backend-client.js   127.0.0.1 25577 "$drasl_root" "$drasl_root" "$username" "$password"   "$player_name" "$player_uuid" "$identity_json" drasl

backend_seen=0
for _ in $(seq 1 60); do
  if grep -Fq "$player_name" "$backend_log" 2>/dev/null; then backend_seen=1; break; fi
  sleep 0.25
done
test "$backend_seen" -eq 1

printf 'save-all\n' >&9
playerdata="$backend/world/playerdata/$player_uuid.dat"
for _ in $(seq 1 60); do
  [[ -s "$playerdata" ]] && break
  sleep 0.25
done
if [[ ! -s "$playerdata" ]]; then
  find "$backend/world/playerdata" -maxdepth 1 -type f -print 2>/dev/null || true
  cat "$backend_log"
  echo "backend did not persist provider-issued UUID $player_uuid" >&2
  exit 1
fi

echo "drasl_vanillacord_backend=PASS"
echo "backend_playerdata=$player_uuid.dat"
