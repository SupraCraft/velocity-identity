#!/usr/bin/env bash
set -euo pipefail

VELOCITY_VERSION="${VELOCITY_VERSION:-4.2.0}"
MINECRAFT_VERSION="${MINECRAFT_VERSION:-1.21.4}"
BRIDGE_REPO="${BRIDGE_REPO:-https://github.com/SupraCraft/Bridge.git}"
BRIDGE_SHA="${BRIDGE_SHA:-5c07448179761d330957d086ab2c2f84793f9528}"
BRIDGE_VERSION="${BRIDGE_VERSION:-0.1.2-dev}"
VANILLACORD_REPO="${VANILLACORD_REPO:-https://github.com/SupraCraft/VanillaCord.git}"
VANILLACORD_SHA="${VANILLACORD_SHA:-ae95c0e64c4b867a60909b71bd2eb8d17051a5e5}"
MCP_VERSION="${MCP_VERSION:-1.68.0}"

root="$(pwd)"
work="$root/build/vanillacord-integration"
backend="$work/backend"
node_dir="$work/node"
velocity_run="$root/run/velocity-$VELOCITY_VERSION"
backend_log="$work/backend.log"
velocity_log="$work/velocity.log"
identity_json="$work/assigned-identity.json"
secret='velocity-identity-integration-only-7f06b7f6f02f4a48811bdf8f6db0d3a4'

rm -rf "$work" "$velocity_run"
mkdir -p "$work" "$backend" "$node_dir" "$velocity_run/plugins/velocityidentity"

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

echo "bridge_sha=$BRIDGE_SHA"
echo "vanillacord_sha=$VANILLACORD_SHA"
echo "minecraft_version=$MINECRAFT_VERSION"
echo "velocity_version=$VELOCITY_VERSION"

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
  ./mvnw -B -DskipTests package \
    -Dmaven.repo.local="$shared_maven_repo" \
    -Dbridge.owner=SupraCraft \
    -Dbridge.version="$BRIDGE_VERSION"
)

mapfile -t vanillacord_jars < <(find "$work/VanillaCord/artifacts" -maxdepth 1 -type f -name 'supracraft-vanillacord-*.jar' -print | LC_ALL=C sort)
if [[ "${#vanillacord_jars[@]}" -ne 1 ]]; then
  printf 'Expected one VanillaCord artifact, found %d\n' "${#vanillacord_jars[@]}" >&2
  printf '%s\n' "${vanillacord_jars[@]}" >&2
  exit 1
fi
vanillacord_jar="${vanillacord_jars[0]}"
echo "vanillacord_artifact_sha256=$(sha256sum "$vanillacord_jar" | awk '{print $1}')"

(
  cd "$work/VanillaCord"
  rm -f "out/$MINECRAFT_VERSION.jar"
  java -jar "$vanillacord_jar" "$MINECRAFT_VERSION"
  test -s "out/$MINECRAFT_VERSION.jar"
  cp "out/$MINECRAFT_VERSION.jar" "$backend/server.jar"
)

cat >"$backend/eula.txt" <<'EOF'
eula=true
EOF

cat >"$backend/server.properties" <<'EOF'
online-mode=false
enforce-secure-profile=false
server-ip=127.0.0.1
server-port=25566
network-compression-threshold=-1
motd=VelocityIdentity VanillaCord integration
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
for _ in $(seq 1 120); do
  if grep -Eq 'Done \([^)]*\)!|Done \(' "$backend_log" 2>/dev/null; then
    backend_ready=1
    break
  fi
  if ! kill -0 "$backend_pid" 2>/dev/null; then
    break
  fi
  sleep 1
done
if [[ "$backend_ready" -ne 1 ]]; then
  tail -n 160 "$backend_log" || true
  echo "VanillaCord backend failed to reach ready state" >&2
  exit 1
fi
echo "vanillacord_backend_ready=PASS"

cat >"$velocity_run/velocity.toml" <<'EOF'
config-version = "2.9"
bind = "127.0.0.1:25577"
motd = "<green>VelocityIdentity integration"
show-max-players = 10
online-mode = true
force-key-authentication = true
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
cat >"$velocity_run/plugins/velocityidentity/velocity-identity.properties" <<'EOF'
default.class=MICROSOFT
default.servers=*
host.127.0.0.1.class=GUEST
host.127.0.0.1.servers=backend
EOF

timeout --signal=TERM --kill-after=10s 120s \
  ./gradlew --no-daemon runVelocity -PvelocityVersion="$VELOCITY_VERSION" \
  >"$velocity_log" 2>&1 &
velocity_pid=$!

velocity_ready=0
for _ in $(seq 1 120); do
  if grep -Fq "VelocityIdentity reconciliation trigger=startup readiness=READY" "$velocity_log" 2>/dev/null \
      && grep -Fq "Listening on " "$velocity_log" 2>/dev/null; then
    velocity_ready=1
    break
  fi
  if ! kill -0 "$velocity_pid" 2>/dev/null; then
    break
  fi
  sleep 0.5
done
if [[ "$velocity_ready" -ne 1 ]]; then
  tail -n 160 "$velocity_log" || true
  echo "Velocity integration proxy failed to reach ready/listening state" >&2
  exit 1
fi
echo "velocity_ready=PASS"

npm install --no-audit --no-fund --ignore-scripts --prefix "$node_dir" "minecraft-protocol@$MCP_VERSION"
resolved_mcp="$(NODE_PATH="$node_dir/node_modules" node -p "require('minecraft-protocol/package.json').version")"
test "$resolved_mcp" = "$MCP_VERSION"
echo "minecraft_protocol_version=$resolved_mcp"

NODE_PATH="$node_dir/node_modules" node scripts/vanillacord-client.js 127.0.0.1 25577 "$identity_json"

assigned_name="$(python3 - "$identity_json" <<'PY'
import json, sys
with open(sys.argv[1], encoding='utf-8') as handle:
    print(json.load(handle)['assigned_name'])
PY
)"

backend_seen=0
for _ in $(seq 1 40); do
  if grep -Fq "$assigned_name" "$backend_log" 2>/dev/null; then
    backend_seen=1
    break
  fi
  sleep 0.25
done
if [[ "$backend_seen" -ne 1 ]]; then
  echo "Backend never logged the proxy-issued GameProfile name: $assigned_name" >&2
  tail -n 160 "$backend_log" || true
  tail -n 160 "$velocity_log" || true
  exit 1
fi

echo "vanillacord_forwarded_identity=PASS"
echo "backend_seen_name=$assigned_name"
