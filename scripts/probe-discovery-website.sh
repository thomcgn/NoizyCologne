#!/usr/bin/env bash
# Optional live, read-only probe. Never invoked by CI or the ordinary quality gate.
set -Eeuo pipefail
(( $# == 1 )) || { echo 'Usage: scripts/probe-discovery-website.sh https://official-website.example/' >&2; exit 64; }
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"
[[ -f backend/target/quality-classpath.txt && -f backend/target/test-classes/de/noizycologne/backend/discovery/DiscoveryWebsiteProbe.class ]] || {
  echo 'Compile backend test sources and prepare the dependency classpath first; see docs/BROWSER-CRAWLER.md.' >&2; exit 1;
}
probe_id="noizy-website-probe-$RANDOM-$RANDOM"
gateway="$probe_id-gateway"
worker="$probe_id-worker"
sandbox="$probe_id-sandbox"
egress="$probe_id-egress"
export BROWSER_WORKER_TOKEN
BROWSER_WORKER_TOKEN=$(python3 -c 'import secrets; print(secrets.token_urlsafe(36))')
cleanup() {
  status=$?
  trap - EXIT
  docker rm -f "$gateway" "$worker" >/dev/null 2>&1 || true
  docker network rm "$sandbox" "$egress" >/dev/null 2>&1 || true
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
docker build -t noizycologne-browser-gateway:quality -f browser-worker/Dockerfile.gateway browser-worker > /dev/null
docker build -t noizycologne-browser-worker:quality browser-worker > /dev/null
docker network create --internal "$sandbox" > /dev/null
docker network create "$egress" > /dev/null
docker run -d --init --name "$gateway" --network "$egress" --read-only --cap-drop ALL \
  --security-opt no-new-privileges:true --memory 128m --cpus 0.25 --pids-limit 64 \
  --sysctl net.ipv4.ip_forward=0 -e BROWSER_WORKER_TOKEN -p 127.0.0.1::3128 \
  noizycologne-browser-gateway:quality > /dev/null
docker network connect --alias browser-gateway "$sandbox" "$gateway"
docker run -d --init --name "$worker" --network "$sandbox" --network-alias browser-worker \
  --read-only --cap-drop ALL --security-opt no-new-privileges:true \
  --security-opt "seccomp=$root/browser-worker/seccomp_profile.json" \
  --memory 768m --cpus 1 --pids-limit 256 --shm-size 256m \
  --tmpfs /tmp:rw,size=134217728,mode=1777 -e BROWSER_WORKER_TOKEN \
  noizycologne-browser-worker:quality > /dev/null
port=$(docker port "$gateway" 3128/tcp)
[[ "$port" =~ ^127\.0\.0\.1:([0-9]+)$ ]]
export DISCOVERY_PROBE_BROWSER_URL="http://127.0.0.1:${BASH_REMATCH[1]}/render"
python3 - "$root" "$1" <<'PY'
from pathlib import Path
import os,subprocess,sys,time,urllib.request
root=Path(sys.argv[1])
health=os.environ['DISCOVERY_PROBE_BROWSER_URL'].removesuffix('/render')+'/health'
for attempt in range(20):
    try:
        urllib.request.urlopen(health,timeout=2).close()
        break
    except OSError:
        time.sleep(.2)
else:
    raise SystemExit('Local test gateway did not become ready')
dependencies=(root/'backend/target/quality-classpath.txt').read_text().strip()
classpath=os.pathsep.join([str(root/'backend/target/test-classes'),str(root/'backend/target/classes'),dependencies])
mockito=next(path for path in dependencies.split(os.pathsep) if Path(path).name.startswith('mockito-core-'))
raise SystemExit(subprocess.run(['java','-Xshare:off','-javaagent:'+mockito,'-cp',classpath,'de.noizycologne.backend.discovery.DiscoveryWebsiteProbe',sys.argv[2]],cwd=root,timeout=180).returncode)
PY
