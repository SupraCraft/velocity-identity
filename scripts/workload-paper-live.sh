#!/usr/bin/env bash
set -euo pipefail

VELOCITY_VERSION="${VELOCITY_VERSION:-4.2.0}"
PAPER_VERSION="${PAPER_VERSION:-1.21.4}"
PAPER_BUILD="${PAPER_BUILD:-232}"
PAPER_SHA256="${PAPER_SHA256:-5ee4f542f628a14c644410b08c94ea42e772ef4d29fe92973636b6813d4eaffc}"
PAPER_URL="${PAPER_URL:-https://fill-data.papermc.io/v1/objects/$PAPER_SHA256/paper-$PAPER_VERSION-$PAPER_BUILD.jar}"
MCP_VERSION="${MCP_VERSION:-1.68.0}"

root="$(pwd)"
work="$root/build/workload-paper-live"
paper="$work/paper"
node_dir="$work/node"
paper_log="$work/paper.log"
velocity_log="$work/velocity.log"
velocity_run="$root/run/velocity-$VELOCITY_VERSION"
plugin_dir="$velocity_run/plugins/velocityidentity"
private_key="$work/workload-private.pem"
identity_json="$work/workload-identity.json"
forwarding_secret='vip-workload-paper-8f3121d9'
key_id='gha-bot-1'
subject='gha/workload/bot-1'
issuer='urn:supracraft:vip:gha'
game_name='VipBot01'

rm -rf "$work" "$velocity_run"
mkdir -p "$paper" "$node_dir" "$plugin_dir"

cleanup() {
  kill "${velocity_pid:-}" 2>/dev/null || true
  if [[ -n "${paper_pid:-}" ]]; then
    printf 'stop\n' >&9 2>/dev/null || true
    sleep 1
    kill "$paper_pid" 2>/dev/null || true
  fi
}
trap cleanup EXIT

openssl genpkey -algorithm ED25519 -out "$private_key" >/dev/null 2>&1
public_key_b64="$(openssl pkey -in "$private_key" -pubout -outform DER 2>/dev/null | base64 -w0)"
game_uuid="$(python3 - <<'PY'
import uuid
print(uuid.uuid4())
PY
)"
echo "external_key_generation=PASS"
echo "workload_uuid=$game_uuid"
echo "workload_name=$game_name"

curl -fL -A 'SupraCraft-VIP-Qualification/0.1 (https://github.com/SupraCraft/velocity-identity)'   "$PAPER_URL" -o "$paper/server.jar"
test "$(sha256sum "$paper/server.jar" | awk '{print $1}')" = "$PAPER_SHA256"

cat >"$paper/eula.txt" <<'EOF'
eula=true
EOF
cat >"$paper/server.properties" <<'EOF'
online-mode=false
enforce-secure-profile=false
server-ip=127.0.0.1
server-port=25566
network-compression-threshold=-1
motd=VIP workload Paper qualification
level-name=world
view-distance=2
simulation-distance=2
spawn-protection=0
enable-status=false
EOF

mkfifo "$work/paper-init.in"
exec 8<>"$work/paper-init.in"
(
  cd "$paper"
  PAPER_VELOCITY_SECRET="$forwarding_secret" java -Xms256M -Xmx1536M -jar server.jar nogui <&8 >"$work/paper-init.log" 2>&1
) &
init_pid=$!
for _ in $(seq 1 150); do
  grep -Eq 'Done \([^)]*\)!|Done \(' "$work/paper-init.log" 2>/dev/null && break
  kill -0 "$init_pid" 2>/dev/null || { cat "$work/paper-init.log"; exit 1; }
  sleep 1
done
grep -Eq 'Done \([^)]*\)!|Done \(' "$work/paper-init.log"
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
for _ in $(seq 1 150); do
  grep -Eq 'Done \([^)]*\)!|Done \(' "$paper_log" 2>/dev/null && break
  kill -0 "$paper_pid" 2>/dev/null || { cat "$paper_log"; exit 1; }
  sleep 1
done
grep -Eq 'Done \([^)]*\)!|Done \(' "$paper_log"
echo "paper_workload_backend=READY"

npm install --no-audit --no-fund --ignore-scripts --prefix "$node_dir" "minecraft-protocol@$MCP_VERSION" >/dev/null
resolved_mcp="$(NODE_PATH="$node_dir/node_modules" node -p "require('minecraft-protocol/package.json').version")"
test "$resolved_mcp" = "$MCP_VERSION"
echo "minecraft_protocol_version=$resolved_mcp"
echo "node_version=$(node --version)"

cat >"$velocity_run/velocity.toml" <<'EOF'
config-version = "2.9"
bind = "127.0.0.1:25577"
motd = "<green>VIP workload qualification"
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
host.workload.vip.test.class=WORKLOAD
host.workload.vip.test.servers=backend
EOF

cat >"$plugin_dir/workloads.properties" <<EOF
issuer=$issuer
workload.$key_id.subject=$subject
workload.$key_id.public-key=$public_key_b64
workload.$key_id.uuid=$game_uuid
workload.$key_id.name=$game_name
EOF

timeout --signal=TERM --kill-after=10s 150s   ./gradlew --no-daemon runVelocity -PvelocityVersion="$VELOCITY_VERSION"   >"$velocity_log" 2>&1 &
velocity_pid=$!

for _ in $(seq 1 220); do
  if grep -Fq "VelocityIdentity reconciliation trigger=startup readiness=READY" "$velocity_log" 2>/dev/null       && grep -Fq "Listening on " "$velocity_log" 2>/dev/null; then
    break
  fi
  kill -0 "$velocity_pid" 2>/dev/null || { cat "$velocity_log"; exit 1; }
  sleep 0.5
done
grep -Fq "VelocityIdentity reconciliation trigger=startup readiness=READY" "$velocity_log"

python3 - "$plugin_dir/state/workload-trust.json" "$issuer" <<'PY'
import json,sys
x=json.load(open(sys.argv[1]))
assert x['issuer']==sys.argv[2], x
assert x['bindingCount']==1, x
assert x['keyIds']==['gha-bot-1'], x
print('workload_trust_reconciliation=PASS')
PY

NODE_PATH="$node_dir/node_modules" node scripts/workload-backend-client.js   127.0.0.1 25577 "$private_key" "$key_id" "$game_name" "$game_uuid" "$identity_json" workload.vip.test

grep -Fq "VIP admitted provider=vip-workload-ed25519 issuer=$issuer gameUuid=$game_uuid gameName=$game_name" "$velocity_log"

printf 'save-all\n' >&9
for _ in $(seq 1 80); do
  [[ -s "$paper/world/playerdata/$game_uuid.dat" ]] && break
  sleep 0.25
done
test -s "$paper/world/playerdata/$game_uuid.dat"
echo "workload_paper_identity=PASS"

baseline="$(find "$paper/world/playerdata" -maxdepth 1 -type f -name '*.dat' | wc -l | tr -d ' ')"
for mode in bad-signature unknown-key replay missing-response; do
  NODE_PATH="$node_dir/node_modules" node scripts/workload-negative-client.js     127.0.0.1 25577 "$private_key" "$key_id" "$mode" workload.vip.test
done
sleep 1
after="$(find "$paper/world/playerdata" -maxdepth 1 -type f -name '*.dat' | wc -l | tr -d ' ')"
test "$after" = "$baseline"
echo "workload_negative_backend_isolation=PASS"
echo "workload_live_end_to_end=PASS"
