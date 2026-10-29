#!/usr/bin/env bash
# Linux server only. Sourceable for tests without a server.
set -Eeuo pipefail
fail() { printf 'DEPLOY ERROR: %s\n' "$*" >&2; return 1; }

find_app_pids() {
    local entry arg previous cwd executable
    for entry in /proc/[0-9]*; do
        [[ -O "$entry" && -r "$entry/cmdline" ]] || continue
        executable=$(readlink "$entry/exe" 2>/dev/null) || continue
        [[ ${executable##*/} == java ]] || continue
        previous=''
        while IFS= read -r -d '' arg; do
            if [[ $previous == -jar ]]; then
                cwd=$(readlink "$entry/cwd" 2>/dev/null) || break
                if [[ $arg == "$APP_DIR/yeogidot.jar" || ($arg == yeogidot.jar && $cwd == "$APP_DIR") ]]; then
                    printf '%s\n' "${entry##*/}"
                fi
                break
            fi
            previous=$arg
        done < "$entry/cmdline"
    done
}

stop_app() {
    local pid current deadline
    local -a pids=()
    mapfile -t pids < <(find_app_pids)
    for pid in "${pids[@]}"; do kill -TERM "$pid" 2>/dev/null || true; done
    deadline=$((SECONDS + STOP_TIMEOUT))
    while :; do
        current=$(find_app_pids)
        [[ -z $current ]] && return 0
        (( SECONDS < deadline )) || { fail 'Process did not stop; no forced kill or file replacement.'; return 1; }
        sleep 1
    done
}

start_app() {
    local release=$1 log_file=$2
    (
        trap - ERR INT TERM HUP
        cd "$APP_DIR" || exit 1
        # Java must not inherit the deploy lock or SSH input.
        nohup java -Xms512m -Xmx2g -Dspring.profiles.active=prod \
            "-Ddeployment.release=$release" -jar "$APP_DIR/yeogidot.jar" \
            </dev/null >>"$log_file" 2>&1 9>&- &
        echo "$!" > "$APP_DIR/app.pid"
    )
}

app_alive() {
    local pid
    pid=$(cat "$APP_DIR/app.pid") || return 1
    [[ $pid =~ ^[0-9]+$ ]] || return 1
    find_app_pids | grep -Fxq "$pid"
}

process_exists() {
    local pid
    [[ -f "$APP_DIR/app.pid" ]] || return 1
    pid=$(cat "$APP_DIR/app.pid") || return 1
    [[ $pid =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null
}

wait_ready() {
    local expected=$1 response deadline=$((SECONDS + HEALTH_TIMEOUT))
    while (( SECONDS < deadline )); do
        # A just-forked process may not have exec'd java yet.
        if app_alive && response=$(curl --silent --show-error --fail --noproxy '*' \
                --connect-timeout 2 --max-time 3 --write-out '\n%{http_code}' "$HEALTH_URL" 2>/dev/null); then
            [[ $response == "$expected"$'\n200' ]] && return 0
        fi
        process_exists || return 1
        sleep 1
    done
    return 1
}

rollback() {
    echo 'Deployment failed; restoring previous files.' >&2
    stop_app || { fail 'Cannot stop new process. Manual recovery required.'; return 1; }
    if [[ ! -f "$BACKUP/yeogidot.jar" ]]; then
        fail 'No previous JAR exists. Manual recovery required.'; return 1
    fi
    cp -p "$BACKUP/yeogidot.jar" "$APP_DIR/yeogidot.jar" || return 1
    if [[ -f "$BACKUP/r2-ops" ]]; then
        cp -p "$BACKUP/r2-ops" "$APP_DIR/r2-ops" || return 1
    else
        rm -f -- "$APP_DIR/r2-ops"
    fi
    if [[ -f "$BACKUP/deployment.release" ]]; then
        cp -p "$BACKUP/deployment.release" "$APP_DIR/deployment.release" || return 1
    else
        rm -f -- "$APP_DIR/deployment.release"
    fi
    local previous=legacy
    [[ ! -f "$BACKUP/deployment.release" ]] || previous=$(cat "$BACKUP/deployment.release")
    start_app "$previous" "$APP_DIR/logs/rollback-$RELEASE.log" || return 1
    if ! wait_ready "$previous"; then
        fail "Previous JAR restored, but HTTP recovery NOT confirmed. See logs/rollback-$RELEASE.log (legacy JAR may lack /health/ready)."
        return 1
    fi
    echo 'Previous version HTTP recovery confirmed.' >&2
}

on_failure() {
    local code=$?
    trap - ERR INT TERM HUP
    if [[ ${MUTATING:-false} == true ]]; then
        rollback || echo 'ROLLBACK UNCONFIRMED: manual inspection required.' >&2
    fi
    echo "Deployment failed. Backup: ${BACKUP:-not-created}" >&2
    (( code != 0 )) || code=1
    exit "$code"
}

deploy() {
    umask 077
    APP_DIR=$1 INCOMING=$2 RELEASE=$3 HEALTH_URL=${4:-http://127.0.0.1:8080/health/ready}
    HEALTH_TIMEOUT=${HEALTH_TIMEOUT:-90} STOP_TIMEOUT=${STOP_TIMEOUT:-30}
    [[ $RELEASE =~ ^[a-zA-Z0-9_-]+$ ]] || { fail 'Invalid release ID'; return 1; }
    [[ $HEALTH_URL =~ ^http://127\.0\.0\.1:[0-9]+/health/ready$ ]] || { fail 'Health URL must be loopback /health/ready'; return 1; }
    [[ $HEALTH_TIMEOUT =~ ^[1-9][0-9]*$ && $STOP_TIMEOUT =~ ^[1-9][0-9]*$ ]] || return 1
    local tool
    for tool in java curl flock sha256sum unzip; do command -v "$tool" >/dev/null || return 1; done
    APP_DIR=$(cd "$APP_DIR" && pwd -P)
    INCOMING=$(cd "$INCOMING" && pwd -P)
    [[ $APP_DIR != "$INCOMING" ]] || return 1
    exec 9>"$APP_DIR/.deploy.lock"
    flock -n 9 || { fail 'Another deployment is running'; return 1; }
    (cd "$INCOMING" && sha256sum --check SHA256SUMS) || return 1
    unzip -tq "$INCOMING/yeogidot.jar" >/dev/null || return 1
    bash -n "$INCOMING/r2-ops" || return 1
    BACKUP="$APP_DIR/deploy-backups/$RELEASE"
    [[ ! -e $BACKUP ]] || { fail 'Release already attempted; use a new run ID'; return 1; }
    mkdir -p "$APP_DIR/deploy-backups" "$APP_DIR/logs"
    mkdir "$BACKUP"
    local file
    for file in yeogidot.jar r2-ops deployment.release; do
        [[ ! -f "$APP_DIR/$file" ]] || cp -p "$APP_DIR/$file" "$BACKUP/$file"
    done
    MUTATING=true
    trap on_failure ERR
    trap 'false' INT TERM HUP
    stop_app
    cp "$INCOMING/yeogidot.jar" "$APP_DIR/yeogidot.jar"
    cp "$INCOMING/r2-ops" "$APP_DIR/r2-ops"
    chmod 700 "$APP_DIR/r2-ops"
    printf '%s\n' "$RELEASE" > "$APP_DIR/deployment.release"
    start_app "$RELEASE" "$APP_DIR/logs/deploy-$RELEASE.log"
    wait_ready "$RELEASE"
    MUTATING=false
    trap - ERR INT TERM HUP
    echo "Deployment ready: $RELEASE"
}

if [[ ${BASH_SOURCE[0]} == "$0" ]]; then
    [[ $# -ge 3 && $# -le 4 ]] || { echo 'Usage: deploy-app.sh APP_DIR INCOMING RELEASE [HEALTH_URL]' >&2; exit 2; }
    deploy "$@"
fi
