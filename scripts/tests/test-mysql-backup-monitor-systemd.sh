#!/usr/bin/env bash
# Local Linux root test: fake jobs and local status files, no network or production DB.
set -Eeuo pipefail
[[ $EUID == 0 && $(ps -p 1 -o comm=) == systemd ]] || exit 2
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
work=$(mktemp -d /run/yeogidot-monitor-test.XXXXXXXX)
unit="ymt-${work##*.}"
cleanup() {
    local rc=$?
    trap - EXIT
    systemctl stop "$unit-db.service" "$unit-upload.service" "$unit-notify@success.service" "$unit-notify@fail.service" >/dev/null 2>&1 || true
    rm -f -- "/run/systemd/system/$unit-db.service" "/run/systemd/system/$unit-upload.service" "/run/systemd/system/$unit-notify@.service"
    systemctl daemon-reload
    systemctl reset-failed "$unit-db.service" "$unit-upload.service" "$unit-notify@success.service" "$unit-notify@fail.service" >/dev/null 2>&1 || true
    [[ $work == /run/yeogidot-monitor-test.* && ! -L $work ]] || exit 1
    rm -rf -- "$work"
    exit "$rc"
}
trap cleanup EXIT
cat > "$work/job.py" <<'PY'
import pathlib,sys
p=pathlib.Path(sys.argv[1]);kind=sys.argv[2]
if kind in ('success','fail'):
    (p/'notified').write_text(kind)
else:
    (p/(kind+'-ran')).touch()
    if (p/'mode').read_text().strip()==kind: sys.exit(1)
PY
for kind in db upload; do
    cat > "/run/systemd/system/$unit-$kind.service" <<EOF
[Unit]
Description=Local backup monitor hook test
[Service]
Type=oneshot
ExecStart=/usr/bin/python3 $work/job.py $work $kind
EOF
    if [[ $kind == db ]]; then
        cat "$ROOT/scripts/backup-config/mysql-backup-r2.conf" "$ROOT/scripts/backup-config/mysql-backup-monitor.conf"
    else
        cat "$ROOT/scripts/backup-config/mysql-backup-upload-monitor.conf"
    fi | sed -e "s/mysql-backup-upload.service/$unit-upload.service/g" -e "s/mysql-backup-notify@/$unit-notify@/g" >> "/run/systemd/system/$unit-$kind.service"
done
cat > "/run/systemd/system/$unit-notify@.service" <<EOF
[Service]
Type=oneshot
ExecStart=/usr/bin/python3 $work/job.py $work %i
EOF
systemctl daemon-reload
for mode in none db upload; do
    systemctl reset-failed "$unit-db.service" "$unit-upload.service" "$unit-notify@success.service" "$unit-notify@fail.service" >/dev/null 2>&1 || true
    rm -f -- "$work/notified" "$work/db-ran" "$work/upload-ran"
    printf '%s' "$mode" > "$work/mode"
    systemctl start "$unit-db.service" >/dev/null 2>&1 || [[ $mode == db ]]
    for ((i=0;i<50;i++)); do [[ ! -f $work/notified ]] || break; sleep 0.1; done
    expected=fail
    [[ $mode != none ]] || expected=success
    [[ $(cat "$work/notified") == "$expected" ]]
    [[ -f $work/db-ran ]]
    if [[ $mode == db ]]; then [[ ! -f $work/upload-ran ]]; else [[ -f $work/upload-ran ]]; fi
    echo "PASS hook scenario=$mode notification=$expected"
done
