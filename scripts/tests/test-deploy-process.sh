#!/usr/bin/env bash
# Linux integration test: real processes, HTTP and flock; Python stand-in for the JVM.
set -Eeuo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
TEMP=$(mktemp -d)
cleanup() {
    for file in "$TEMP"/*/app/app.pid; do
        [[ -f $file ]] || continue
        pid=$(cat "$file")
        # Only processes launched from this test's interpreter can be stopped.
        [[ $(readlink "/proc/$pid/exe" 2>/dev/null || true) != "$TEMP/bin/java" ]] || kill "$pid" 2>/dev/null || true
    done
    rm -rf -- "$TEMP"
}
trap cleanup EXIT
mkdir "$TEMP/bin"
cp "$(readlink -f "$(command -v python3)")" "$TEMP/bin/java"
export TEST_BIN="$TEMP/bin" TEST_ROOT="$TEMP" DEPLOY_SCRIPT="$ROOT/scripts/deploy-app.sh"
cat > "$TEMP/server.py" <<'PY'
import http.server, sys, pathlib, time
release = next(x.split('=', 1)[1] for x in sys.argv if x.startswith('-Ddeployment.release='))
jar = pathlib.Path(sys.argv[sys.argv.index('-jar') + 1])
behavior = jar.read_text()
if behavior == 'exit':
    sys.exit(1)
if behavior == 'timeout':
    time.sleep(60)
class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.end_headers()
        self.wfile.write(release.encode())
    def log_message(self, *_): pass
http.server.HTTPServer(('127.0.0.1', int(sys.argv[-1])), Handler).serve_forever()
PY
cat > "$TEMP/runner.sh" <<'SH'
source "$DEPLOY_SCRIPT"
PATH="$TEST_BIN:$PATH"
# Fixture files are text, not real JARs. Real script still validates checksums.
unzip() { return 0; }
start_app() {
    (cd "$APP_DIR"
        nohup "$TEST_BIN/java" "$TEST_ROOT/server.py" "-Ddeployment.release=$1" \
            -jar "$APP_DIR/yeogidot.jar" "$PORT" </dev/null >>"$2" 2>&1 9>&- &
        echo "$!" > "$APP_DIR/app.pid"
    )
}
deploy "$1" "$2" "$3" "http://127.0.0.1:$PORT/health/ready"
SH

for scenario in success exit timeout; do
    mkdir -p "$TEMP/$scenario/app" "$TEMP/$scenario/incoming"
    app="$TEMP/$scenario/app" incoming="$TEMP/$scenario/incoming"
    PORT=$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()')
    export PORT HEALTH_TIMEOUT=5 STOP_TIMEOUT=5
    printf success > "$incoming/yeogidot.jar"
    printf '#!/bin/bash\nexit 0\n' > "$incoming/r2-ops"
    (cd "$incoming" && sha256sum yeogidot.jar r2-ops > SHA256SUMS)
    bash "$TEMP/runner.sh" "$app" "$incoming" old > "$TEMP/$scenario/old.log" 2>&1
    old_pid=$(cat "$app/app.pid")
    printf '%s' "$scenario" > "$incoming/yeogidot.jar"
    (cd "$incoming" && sha256sum yeogidot.jar r2-ops > SHA256SUMS)
    status=0
    bash "$TEMP/runner.sh" "$app" "$incoming" new > "$TEMP/$scenario/new.log" 2>&1 || status=$?
    expected=old
    if [[ $scenario == success ]]; then [[ $status == 0 ]]; expected=new
    else [[ $status != 0 ]]; grep -q 'Previous version HTTP recovery confirmed' "$TEMP/$scenario/new.log"
    fi
    [[ $(curl --fail --silent --max-time 2 "http://127.0.0.1:$PORT/health/ready") == "$expected" ]]
    [[ $(cat "$app/app.pid") != "$old_pid" ]]
    # Verify a real competing lock fails before stopping the healthy process.
    current_pid=$(cat "$app/app.pid")
    flock "$app/.deploy.lock" bash -c 'touch "$1"; sleep 10' _ "$TEMP/locked" &
    lock_pid=$!
    while [[ ! -f "$TEMP/locked" ]]; do sleep 0.1; done
    status=0
    bash "$TEMP/runner.sh" "$app" "$incoming" competing > "$TEMP/$scenario/locked.log" 2>&1 || status=$?
    [[ $status != 0 && $(cat "$app/app.pid") == "$current_pid" ]]
    kill "$lock_pid" 2>/dev/null || true
    wait "$lock_pid" 2>/dev/null || true
    rm -f "$TEMP/locked"
    kill "$current_pid"
    echo "PASS real process/HTTP: $scenario and deployment lock"
done
