#!/usr/bin/env bash
set -euo pipefail

velocity_version="${1:-4.2.0}"
aster_sha="${ASTER_SHA:-582418c3082a5fd8d8177ca31d2cb63c2b9a3a79}"
port="${VIP_ASTER_PORT:-18083}"
root="$(pwd)"
work="$root/build/aster-live-smoke"
src="$work/src"
cookies="$work/cookies.txt"
setup_json="$work/setup.json"
profile_json="$work/profile.json"
service_log="$work/aster.log"
velocity_log="$work/velocity.log"
run_dir="$root/run/velocity-$velocity_version"
plugin_dir="$run_dir/plugins/velocityidentity"
service="http://127.0.0.1:$port"
auth_root="$service/api/yggdrasil/authserver"
session_root="$service/api/yggdrasil/sessionserver"
has_joined="$session_root/session/minecraft/hasJoined"
username="vipgha"
email="vip-gha@example.com"
player="VipAster"
secret="$(python3 - <<'PY'
import secrets
print('Vip-' + secrets.token_hex(12))
PY
)"
container="vip-aster-$GITHUB_RUN_ID-$GITHUB_RUN_ATTEMPT"
image="vip-aster:$aster_sha"

rm -rf "$work" "$run_dir"
mkdir -p "$work" "$plugin_dir"

cleanup() {
  kill "${velocity_pid:-}" 2>/dev/null || true
  docker rm -f "$container" >/dev/null 2>&1 || true
}
trap cleanup EXIT

git init -q "$src"
git -C "$src" remote add origin https://github.com/AsterCommunity/AsterYggdrasil.git
git -C "$src" fetch -q --depth=1 origin "$aster_sha"
git -C "$src" checkout -q --detach FETCH_HEAD
test "$(git -C "$src" rev-parse HEAD)" = "$aster_sha"
echo "aster_sha=$aster_sha"

docker build --pull=false -t "$image" "$src"
image_id=$(docker image inspect "$image" --format '{{.Id}}')
echo "aster_image_id=$image_id"

docker run -d --name "$container" --network host \
  -e ASTER__SERVER__HOST=127.0.0.1 \
  -e ASTER__SERVER__PORT="$port" \
  -e ASTER__AUTH__BOOTSTRAP_INSECURE_COOKIES=true \
  -e 'ASTER__DATABASE__URL=sqlite:///data/asteryggdrasil.db?mode=rwc' \
  "$image" >/dev/null

ready=0
for _ in $(seq 1 240); do
  if curl -fsS "$service/health/ready" >/dev/null 2>&1; then ready=1; break; fi
  if ! docker inspect "$container" >/dev/null 2>&1; then break; fi
  sleep 0.5
done
docker logs "$container" >"$service_log" 2>&1 || true
if [[ "$ready" -ne 1 ]]; then cat "$service_log"; exit 1; fi
echo "aster_ready=PASS"

code=$(curl -sS -o "$setup_json" -c "$cookies" -w '%{http_code}' \
  -H 'Content-Type: application/json' -X POST "$service/api/v1/auth/setup" \
  --data "{\"username\":\"$username\",\"email\":\"$email\",\"password\":\"$secret\",\"public_site_url\":\"$service\"}")
test "$code" = 200
access=$(awk '$6=="aster_access" {print $7}' "$cookies" | tail -n1)
test -n "$access"
echo "aster_setup=PASS"

code=$(curl -sS -o "$profile_json" -w '%{http_code}' \
  -H "Authorization: Bearer $access" -H 'Content-Type: application/json' \
  -X POST "$service/api/v1/profiles/minecraft" --data "{\"name\":\"$player\"}")
test "$code" = 200
read -r uuid name < <(python3 - "$profile_json" <<'PY'
import json,sys
x=json.load(open(sys.argv[1]))
d=x['data']
print(d['id'], d['name'])
PY
)
test "$name" = "$player"
echo "aster_player_create=PASS"
echo "aster_player_uuid=$uuid"

mkdir -p "$work/node"
npm install --no-audit --no-fund --ignore-scripts --prefix "$work/node" minecraft-protocol@1.68.0 >/dev/null

cat >"$plugin_dir/velocity-identity.properties" <<'EOF'
default.class=ONLINE_SESSION
default.servers=*
EOF

JAVA_TOOL_OPTIONS="-Dmojang.sessionserver=$has_joined" timeout --signal=TERM --kill-after=10s 120s ./gradlew --no-daemon runVelocity -PvelocityVersion="$velocity_version" >"$velocity_log" 2>&1 &
velocity_pid=$!

vip_ready=0
for _ in $(seq 1 200); do
  if grep -Fq 'VelocityIdentity reconciliation trigger=startup readiness=READY' "$velocity_log" 2>/dev/null; then vip_ready=1; break; fi
  kill -0 "$velocity_pid" 2>/dev/null || { cat "$velocity_log"; exit 1; }
  sleep 0.5
done
test "$vip_ready" = 1

listen_port=$(grep -F 'Listening on ' "$velocity_log" | tail -n1 | sed -E 's/.*:([0-9]+).*/\1/')
test -n "$listen_port"

NODE_PATH="$work/node/node_modules" node scripts/yggdrasil-live-client.js 127.0.0.1 "$listen_port" "$auth_root" "$session_root" "$username" "$secret" "$player" "$uuid" aster
NODE_PATH="$work/node/node_modules" node scripts/yggdrasil-bad-password-client.js 127.0.0.1 "$listen_port" "$auth_root" "$session_root" "$username" invalid-credential aster

python3 - "$plugin_dir/state/observation.json" "$service/api/yggdrasil" "$has_joined" <<'PY'
import json,sys
x=json.load(open(sys.argv[1]))
assert x['sessionAuthorityIssuer']==sys.argv[2], x
assert x['sessionHasJoinedEndpoint']==sys.argv[3], x
print('aster_authority_observation=PASS')
PY

docker logs "$container" >"$service_log" 2>&1 || true
echo "aster_live_online_session=PASS"
