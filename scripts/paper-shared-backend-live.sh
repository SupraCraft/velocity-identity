#!/usr/bin/env bash
set -euo pipefail

VELOCITY_VERSION="${VELOCITY_VERSION:-4.2.0}"
PAPER_VERSION="${PAPER_VERSION:-1.21.4}"
PAPER_BUILD="${PAPER_BUILD:-232}"
PAPER_SHA256="${PAPER_SHA256:-5ee4f542f628a14c644410b08c94ea42e772ef4d29fe92973636b6813d4eaffc}"
PAPER_URL="${PAPER_URL:-https://fill-data.papermc.io/v1/objects/$PAPER_SHA256/paper-$PAPER_VERSION-$PAPER_BUILD.jar}"
DRASL_SHA="${DRASL_SHA:-1eb9d46189ba365591b0586c79862223165813da}"
MCP_VERSION="${MCP_VERSION:-1.68.0}"

root="$(pwd)"
work="$root/build/paper-shared-backend"
paper="$work/paper"
node_dir="$work/node"
drasl_src="$work/drasl-src"
drasl_state="$work/drasl-state"
drasl_config="$work/drasl.toml"
drasl_log="$work/drasl.log"
paper_log="$work/paper.log"
velocity_log="$work/velocity.log"
velocity_run="$root/run/velocity-$VELOCITY_VERSION"
plugin_dir="$velocity_run/plugins/velocityidentity"
drasl_root="http://127.0.0.1:18081"
has_joined="$drasl_root/session/minecraft/hasJoined"
forwarding_secret='vip-paper-shared-backend-31f0ac49'
username='vip_paper_user'
password='vip-paper-password-123'
player_name='VipPaper'

rm -rf "$work" "$velocity_run"
mkdir -p "$paper" "$node_dir" "$drasl_state"

cleanup() {
  kill "${velocity_pid:-}" 2>/dev/null || true
  if [[ -n "${paper_pid:-}" ]]; then
    printf 'stop\n' >&9 2>/dev/null || true
    sleep 1
    kill "$paper_pid" 2>/dev/null || true
  fi
  kill "${drasl_pid:-}" 2>/dev/null || true
}
trap cleanup EXIT

git init -q "$drasl_src"
git -C "$drasl_src" remote add origin https://github.com/unmojang/drasl.git
git -C "$drasl_src" fetch -q --depth=1 origin "$DRASL_SHA"
git -C "$drasl_src" checkout -q --detach FETCH_HEAD
test "$(git -C "$drasl_src" rev-parse HEAD)" = "$DRASL_SHA"
( cd "$drasl_src" && go build -trimpath -o "$work/drasl" . )

cat >"$drasl_config" <<EOF
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

"$work/drasl" --config "$drasl_config" >"$drasl_log" 2>&1 &
drasl_pid=$!
for _ in $(seq 1 160); do
  curl -fsS "$drasl_root/publickeys" >/dev/null 2>&1 && break
  sleep 0.25
done
curl -fsS "$drasl_root/publickeys" >/dev/null

curl -fsS -H 'Content-Type: application/json' -X POST "$drasl_root/drasl/api/v3/users"   --data-binary @- >"$work/user.json" <<EOF
{"username":"$username","password":"$password","playerName":"$player_name","requestApiToken":true}
EOF
read -r online_uuid _ < <(python3 - "$work/user.json" <<'PY'
import json,sys
p=json.load(open(sys.argv[1]))["user"]["players"][0]
print(p["uuid"],p["name"])
PY
)
echo "online_session_uuid=$online_uuid"

curl -fL -A 'SupraCraft-VIP-Qualification/0.1 (https://github.com/SupraCraft/velocity-identity)'   "$PAPER_URL" -o "$paper/server.jar"
test "$(sha256sum "$paper/server.jar" | awk '{print $1}')" = "$PAPER_SHA256"
echo "paper_build=$PAPER_BUILD"
echo "paper_sha256=$PAPER_SHA256"

cat >"$paper/eula.txt" <<'EOF'
eula=true
EOF
cat >"$paper/server.properties" <<'EOF'
online-mode=false
enforce-secure-profile=false
server-ip=127.0.0.1
server-port=25566
network-compression-threshold=-1
motd=VIP Paper shared identity qualification
level-name=world
view-distance=2
simulation-distance=2
spawn-protection=0
enable-status=false
EOF

# First boot creates Paper's versioned configuration with the exact build schema.
mkfifo "$work/paper-init.in"
exec 8<>"$work/paper-init.in"
(
  cd "$paper"
  PAPER_VELOCITY_SECRET="$forwarding_secret" java -Xms256M -Xmx1536M -jar server.jar nogui <&8 >"$work/paper-init.log" 2>&1
) &
init_pid=$!
init_ready=0
for _ in $(seq 1 150); do
  if grep -Eq 'Done \([^)]*\)!|Done \(' "$work/paper-init.log" 2>/dev/null; then init_ready=1; break; fi
  kill -0 "$init_pid" 2>/dev/null || break
  sleep 1
done
if [[ "$init_ready" -ne 1 ]]; then cat "$work/paper-init.log"; exit 1; fi
printf 'stop\n' >&8
wait "$init_pid"
exec 8>&-

python3 - "$paper/config/paper-global.yml" <<'PY'
import sys
path=sys.argv[1]
lines=open(path,encoding='utf-8').read().splitlines()
out=[]
in_velocity=False
seen_enabled=seen_online=False
for line in lines:
    if line.startswith('  velocity:'):
        in_velocity=True
        out.append(line)
        continue
    if in_velocity and line and not line.startswith('    '):
        in_velocity=False
    if in_velocity and line.lstrip().startswith('enabled:'):
        out.append('    enabled: true'); seen_enabled=True
    elif in_velocity and line.lstrip().startswith('online-mode:'):
        out.append('    online-mode: true'); seen_online=True
    else:
        out.append(line)
assert seen_enabled and seen_online, 'Paper velocity config shape changed'
open(path,'w',encoding='utf-8').write('\n'.join(out)+'\n')
PY

mkfifo "$work/paper.in"
exec 9<>"$work/paper.in"
(
  cd "$paper"
  PAPER_VELOCITY_SECRET="$forwarding_secret" java -Xms256M -Xmx1536M -jar server.jar nogui <&9 >"$paper_log" 2>&1
) &
paper_pid=$!
paper_ready=0
for _ in $(seq 1 150); do
  if grep -Eq 'Done \([^)]*\)!|Done \(' "$paper_log" 2>/dev/null; then paper_ready=1; break; fi
  kill -0 "$paper_pid" 2>/dev/null || break
  sleep 1
done
if [[ "$paper_ready" -ne 1 ]]; then cat "$paper_log"; exit 1; fi
echo "paper_velocity_online_mode_true=READY"

npm install --no-audit --no-fund --ignore-scripts --prefix "$node_dir" "minecraft-protocol@$MCP_VERSION" >/dev/null

rm -rf "$velocity_run"
mkdir -p "$plugin_dir"

cat >"$velocity_run/velocity.toml" <<'EOF'
config-version = "2.9"
bind = "127.0.0.1:25577"
motd = "<green>VIP Paper shared backend"
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

printf '%s\n' "$forwarding_secret" >"$velocity_run/forwarding.secret"
cat >"$plugin_dir/velocity-identity.properties" <<'EOF'
default.class=ONLINE_SESSION
default.servers=backend
host.guest.vip.test.class=GUEST
host.guest.vip.test.servers=backend
EOF

JAVA_TOOL_OPTIONS="-Dmojang.sessionserver=$has_joined" \
  timeout --signal=TERM --kill-after=10s 150s \
  ./gradlew --no-daemon runVelocity -PvelocityVersion="$VELOCITY_VERSION" \
  >"$velocity_log" 2>&1 &
velocity_pid=$!

velocity_ready=0
for _ in $(seq 1 200); do
  if grep -Fq "VelocityIdentity reconciliation trigger=startup readiness=READY" "$velocity_log" 2>/dev/null \
      && grep -Fq "Listening on " "$velocity_log" 2>/dev/null; then
    velocity_ready=1
    break
  fi
  kill -0 "$velocity_pid" 2>/dev/null || break
  sleep 0.5
done
if [[ "$velocity_ready" -ne 1 ]]; then
  cat "$velocity_log"
  echo "Velocity did not reach READY for shared Paper policy" >&2
  exit 1
fi

NODE_PATH="$node_dir/node_modules" node scripts/yggdrasil-backend-client.js \
  127.0.0.1 25577 "$drasl_root" "$drasl_root" "$username" "$password" \
  "$player_name" "$online_uuid" "$work/online.json" drasl-paper

printf 'save-all\n' >&9
for _ in $(seq 1 60); do
  [[ -s "$paper/world/playerdata/$online_uuid.dat" ]] && break
  sleep 0.25
done
test -s "$paper/world/playerdata/$online_uuid.dat"
echo "paper_online_session_identity=PASS"

NODE_PATH="$node_dir/node_modules" node scripts/vanillacord-client.js \
  127.0.0.1 25577 "$work/guest.json" guest.vip.test

guest_uuid=$(python3 - "$work/guest.json" <<'PY'
import json,sys
print(json.load(open(sys.argv[1]))["assigned_uuid"])
PY
)

printf 'save-all\n' >&9
for _ in $(seq 1 60); do
  [[ -s "$paper/world/playerdata/$guest_uuid.dat" ]] && break
  sleep 0.25
done
if [[ ! -s "$paper/world/playerdata/$guest_uuid.dat" ]]; then
  echo "Paper online-mode=true did not persist VIP guest UUID $guest_uuid" >&2
  cat "$paper_log"
  exit 1
fi

echo "paper_guest_identity=PASS"
echo "paper_shared_online_and_synthetic=PASS"
echo "guest_uuid=$guest_uuid"
