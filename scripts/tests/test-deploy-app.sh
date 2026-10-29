#!/usr/bin/env bash
set -Eeuo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
TEMP=$(mktemp -d)
trap 'rm -rf -- "$TEMP"' EXIT
export DEPLOY_SCRIPT="$ROOT/scripts/deploy-app.sh"

# Exercise real file backups, checksums, replacement and error traps.
# Only process, HTTP, JAR-format and lock operations are simulated here.
cat > "$TEMP/runner.sh" <<'RUNNER'
#!/usr/bin/env bash
source "$DEPLOY_SCRIPT"
flock() { [[ $CASE != locked ]]; }
unzip() { return 0; }
stop_app() {
    echo stop >> "$APP_DIR/events"
    [[ $CASE != stop-failure ]]
}
start_app() {
    echo "start:$1" >> "$APP_DIR/events"
    [[ $CASE != start-failure || $1 == old ]]
}
wait_ready() {
    echo "health:$1" >> "$APP_DIR/events"
    [[ $CASE == success || ($1 == old && $CASE != rollback-failure) ]]
}
deploy "$1" "$2" new
RUNNER

for scenario in success health-failure start-failure rollback-failure legacy no-backup corrupt locked stop-failure; do
    dir="$TEMP/$scenario"
    mkdir -p "$dir/app" "$dir/incoming"
    printf 'new jar' > "$dir/incoming/yeogidot.jar"
    printf '#!/bin/bash\necho new\n' > "$dir/incoming/r2-ops"
    if [[ $scenario != no-backup ]]; then
        printf 'old jar' > "$dir/app/yeogidot.jar"
        printf '#!/bin/bash\necho old\n' > "$dir/app/r2-ops"
        [[ $scenario == legacy ]] || printf 'old\n' > "$dir/app/deployment.release"
    fi
    (cd "$dir/incoming" && sha256sum yeogidot.jar r2-ops > SHA256SUMS)
    [[ $scenario != corrupt ]] || printf tampered >> "$dir/incoming/yeogidot.jar"
    status=0
    CASE=$scenario bash "$TEMP/runner.sh" "$dir/app" "$dir/incoming" > "$dir/result" 2>&1 || status=$?
    if [[ $scenario == success ]]; then
        [[ $status == 0 ]]
        [[ $(cat "$dir/app/yeogidot.jar") == 'new jar' ]]
        [[ $(cat "$dir/app/deployment.release") == new ]]
        [[ $(cat "$dir/app/deploy-backups/new/yeogidot.jar") == 'old jar' ]]
    else
        [[ $status != 0 ]]
        if [[ $scenario != no-backup ]]; then
            [[ $(cat "$dir/app/yeogidot.jar") == 'old jar' ]]
        fi
    fi
    case $scenario in
        health-failure|start-failure)
            grep -q 'Previous version HTTP recovery confirmed' "$dir/result"
            [[ $(cat "$dir/app/deployment.release") == old ]]
            grep -q 'echo old' "$dir/app/r2-ops" ;;
        rollback-failure|legacy|no-backup|stop-failure)
            grep -q 'ROLLBACK UNCONFIRMED' "$dir/result" ;;
        corrupt|locked) [[ ! -f "$dir/app/events" ]] ;;
    esac
    echo "PASS: $scenario"
done

# Test the real polling function: exact release body, HTTP failure, dead process.
(
    source "$DEPLOY_SCRIPT"
    HEALTH_URL=http://127.0.0.1:8080/health/ready
    HEALTH_TIMEOUT=1
    app_alive() { return 0; }
    process_exists() { return 0; }
    curl() { printf '%s\n%s' "$BODY" "${HTTP_STATUS:-200}"; return "${HTTP_RESULT:-0}"; }
    BODY=new
    wait_ready new
    BODY=old
    if wait_ready new; then exit 1; fi
    BODY=new HTTP_RESULT=22
    if wait_ready new; then exit 1; fi
    HTTP_RESULT=0 HTTP_STATUS=302
    if wait_ready new; then exit 1; fi
    app_alive() { return 1; }
    process_exists() { return 1; }
    HTTP_RESULT=0
    if wait_ready new; then exit 1; fi
    # Startup grace: process exists before its executable becomes java.
    BODY=new HTTP_STATUS=200 HEALTH_TIMEOUT=3 attempts=0
    process_exists() { return 0; }
    app_alive() { attempts=$((attempts + 1)); [[ $attempts -gt 1 ]]; }
    wait_ready new
)
echo 'PASS: readiness version mismatch, HTTP failure, redirect, process exit'
echo 'Deployment checks passed (simulated processes/HTTP; no server access).'
