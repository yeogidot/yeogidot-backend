#!/usr/bin/env bash
# Root-only local Linux integration test. Uses synthetic data and a private Unix socket.
set -Eeuo pipefail
umask 077
[[ $(uname -s) == Linux && $EUID == 0 ]] || { echo 'Run as root in local Linux with systemd.' >&2; exit 2; }
[[ $(ps -p 1 -o comm=) == systemd ]] || exit 2
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
for tool in mysqld mysql mysqladmin systemctl systemd-analyze useradd userdel runuser; do command -v "$tool" >/dev/null; done
work=$(mktemp -d /run/yeogidot-backup-test.XXXXXXXX)
tag=${work##*.}
account="ydbt${tag,,}"
unit="yeogidot-backup-test-${tag,,}"
created=false
pid=''
sql() {
    local actual
    actual=$(mysql --no-defaults --protocol=SOCKET --socket="$work/mysql.sock" -uroot -NBe 'SELECT @@datadir')
    [[ ${actual%/} == "$work/data" ]] || return 1
    mysql --no-defaults --protocol=SOCKET --socket="$work/mysql.sock" -uroot --batch --skip-column-names "$@"
}
cleanup() {
    local rc=$? safe=true
    trap - EXIT
    systemctl stop "$unit.timer" "$unit.service" >/dev/null 2>&1 || true
    if [[ -n $pid ]]; then
        if kill -0 "$pid" 2>/dev/null; then
            if sql -e 'SELECT 1' >/dev/null 2>&1 && mysqladmin --no-defaults --protocol=SOCKET --socket="$work/mysql.sock" -uroot shutdown >/dev/null 2>&1; then
                wait "$pid" || safe=false
            else safe=false; fi
        else wait "$pid" || true; fi
    fi
    rm -f -- "/run/systemd/system/$unit.service" "/run/systemd/system/$unit.timer"
    systemctl daemon-reload
    systemctl reset-failed "$unit.service" >/dev/null 2>&1 || true
    if [[ $safe == true ]]; then
        [[ $created == false ]] || userdel "$account"
        [[ $work == /run/yeogidot-backup-test.* && ! -L $work ]] || exit 1
        rm -rf -- "$work"
    else
        echo "Cleanup incomplete; inspect local test directory $work" >&2
        rc=1
    fi
    exit "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM HUP
! getent passwd "$account" >/dev/null
useradd --system --no-create-home --shell /usr/sbin/nologin "$account"
created=true
chmod 755 "$work"
install -d -o "$account" -g "$account" -m 700 "$work/config" "$work/backups" "$work/data" "$work/socket"
install -m 755 "$ROOT/scripts/mysql-backup.sh" "$work/mysql-backup.sh"
mysqld --no-defaults --initialize-insecure --user="$account" --basedir=/usr --datadir="$work/data" >"$work/init.log" 2>&1
# No TCP listener, binlog, events, application, Redis or R2 connections.
mysqld --no-defaults --user="$account" --basedir=/usr --datadir="$work/data" --socket="$work/socket/mysql.sock" --pid-file="$work/socket/mysql.pid" --skip-networking --mysqlx=OFF --secure-file-priv=NULL --skip-log-bin --event-scheduler=OFF --console >"$work/server.log" 2>&1 &
pid=$!
# Keep a fixed root-only discovery link; the server socket remains in its owner directory.
ln -s "$work/socket/mysql.sock" "$work/mysql.sock"
ready=false
for ((i=0;i<60;i++)); do
    if sql -e 'SELECT 1' >/dev/null 2>&1; then ready=true; break; fi
    kill -0 "$pid" 2>/dev/null || break
    sleep 1
done
[[ $ready == true ]]
sql -e "CREATE DATABASE yeogidot CHARACTER SET utf8mb4; CREATE TABLE yeogidot.fixture(id INT PRIMARY KEY, value VARCHAR(40)) ENGINE=InnoDB; INSERT INTO yeogidot.fixture VALUES(1,'synthetic'); CREATE USER 'backup_fixture'@'localhost' IDENTIFIED BY 'synthetic-only'; GRANT SELECT, SHOW VIEW, TRIGGER, EVENT ON yeogidot.* TO 'backup_fixture'@'localhost'; GRANT SHOW_ROUTINE ON *.* TO 'backup_fixture'@'localhost';"
printf '[client]\nuser=backup_fixture\npassword=synthetic-only\nprotocol=SOCKET\nsocket=%s/socket/mysql.sock\n' "$work" > "$work/config/client.cnf"
chown "$account:$account" "$work/config/client.cnf"
chmod 600 "$work/config/client.cnf"
sed -e "s/User=yeogidot-backup/User=$account/" -e "s/Group=yeogidot-backup/Group=$account/" -e "s|/usr/local/sbin/yeogidot-mysql-backup|$work/mysql-backup.sh|" -e "s|/etc/yeogidot-backup/client.cnf|$work/config/client.cnf|" -e "s|/var/backups/yeogidot-mysql|$work/backups|g" "$ROOT/scripts/backup-config/mysql-backup.service" > "/run/systemd/system/$unit.service"
# Same daily timer settings and service sandbox; only unit name and test paths differ.
sed "s/Unit=mysql-backup.service/Unit=$unit.service/" "$ROOT/scripts/backup-config/mysql-backup.timer" > "/run/systemd/system/$unit.timer"
chmod 644 "/run/systemd/system/$unit.service" "/run/systemd/system/$unit.timer"
systemd-analyze verify "/run/systemd/system/$unit.service" "/run/systemd/system/$unit.timer"
systemctl daemon-reload
systemctl start "$unit.service"
[[ $(systemctl show "$unit.service" -p Result --value) == success ]]
bundle=$(find "$work/backups" -mindepth 1 -maxdepth 1 -type d -name 'mysql-*' -print -quit)
[[ -n $bundle && $(stat -c %U "$bundle") == "$account" && $(stat -c %a "$bundle") == 700 ]]
[[ $(stat -c %a "$bundle/backup.sql.gz") == 600 ]]
runuser -u "$account" -- bash -c 'cd "$1" && sha256sum -c SHA256SUMS && gzip -t backup.sql.gz' bash "$bundle"
echo 'PASS systemd service: dedicated OS account, Unix socket, read-only DB account, sandbox, output permissions'
# Incorrect credentials file permissions must fail, leaving the completed backup intact.
chmod 640 "$work/config/client.cnf"
if systemctl start "$unit.service" 2>/dev/null; then exit 1; fi
[[ $(systemctl show "$unit.service" -p Result --value) == exit-code ]]
journalctl -u "$unit.service" --no-pager -o cat | grep -q 'FAILED stage=configuration'
[[ -f $bundle/backup.sql.gz ]]
chmod 600 "$work/config/client.cnf"
systemctl reset-failed "$unit.service"
# Connection/authentication failure is recorded without exposing raw client errors.
sql -e "ALTER USER 'backup_fixture'@'localhost' ACCOUNT LOCK;"
if systemctl start "$unit.service" 2>/dev/null; then exit 1; fi
journalctl -u "$unit.service" --no-pager -o cat | grep -q 'FAILED stage=engine-check'
sql -e "ALTER USER 'backup_fixture'@'localhost' ACCOUNT UNLOCK;"
systemctl reset-failed "$unit.service"
echo 'PASS failures: permission rejection, authentication failure, failed unit state and journal, previous backup retained'
systemctl start "$unit.timer"
[[ $(systemctl is-active "$unit.timer") == active ]]
[[ $(systemctl show "$unit.timer" -p NextElapseUSecRealtime --value) != '' ]]
systemctl stop "$unit.timer"
# Trigger the service through a short test-only timer; daily calendar itself is not accelerated.
cat > "/run/systemd/system/$unit.timer" <<EOF
[Unit]
Description=Local backup timer trigger test
[Timer]
OnActiveSec=1s
AccuracySec=1s
Unit=$unit.service
EOF
systemctl daemon-reload
before=$(find "$work/backups" -mindepth 1 -maxdepth 1 -type d -name 'mysql-*' | wc -l)
systemctl start "$unit.timer"
triggered=false
for ((i=0;i<20;i++)); do
    after=$(find "$work/backups" -mindepth 1 -maxdepth 1 -type d -name 'mysql-*' | wc -l)
    if (( after > before )) && [[ $(systemctl show "$unit.service" -p Result --value) == success ]]; then triggered=true; break; fi
    sleep 1
done
[[ $triggered == true ]]
if journalctl -u "$unit.service" --no-pager -o cat | grep -q 'synthetic-only'; then exit 1; fi
echo 'PASS timer: daily Asia/Seoul schedule loads, short local timer executes backup; password absent from journal'
echo 'PASS local service integration; cleanup stops private MySQL and removes test units/account/files'
