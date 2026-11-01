#!/usr/bin/env bash
# Linux or Windows Git Bash + MySQL 8.0. Two isolated servers, never 3306.
set -Eeuo pipefail
umask 077
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
export PATH="/c/Program Files/MySQL/MySQL Server 8.0/bin:$PATH"
unset MYSQL_PWD
export MYSQL_TEST_LOGIN_FILE=/dev/null
TEMP=$(mktemp -d)
if [[ $(uname -s) == Linux ]]; then
    win=$TEMP
    basedir=/usr
else
    win=$(cygpath -m "$TEMP")
    basedir='C:/Program Files/MySQL/MySQL Server 8.0'
fi
declare -A pids=() dirs=([13379]="$win/source" [13380]="$win/restore")
raw_sql() { local port=$1; shift; mysql --no-defaults --protocol=TCP -h127.0.0.1 -P"$port" -uroot --batch --skip-column-names "$@"; }
normalize_path() {
    local path=${1//$'\r'/}
    path=${path//\\//}
    printf '%s' "${path%/}" | tr '[:upper:]' '[:lower:]'
}
assert_datadir() {
    local actual
    actual=$(raw_sql "$1" --raw --execute='SELECT @@datadir' 2>/dev/null) || return 1
    [[ $(normalize_path "$actual") == "$(normalize_path "${dirs[$1]}")" ]] || {
        echo 'REFUSED: server datadir does not match this test run' >&2
        return 1
    }
}
checked_sql() { local port=$1; shift; assert_datadir "$port" || return 1; raw_sql "$port" "$@"; }
sql() { checked_sql 13379 "$@"; }
restore_sql() { checked_sql 13380 "$@"; }
stop_server() {
    local port=$1
    assert_datadir "$port" || return 1
    mysqladmin --no-defaults --protocol=TCP -h127.0.0.1 -P"$port" -uroot shutdown >/dev/null 2>&1 || return 1
    wait "${pids[$port]}" || return 1
    unset 'pids[$port]'
}
cleanup() {
    local rc=$? safe=true port
    trap - EXIT
    for port in "${!pids[@]}"; do
        if ! stop_server "$port"; then safe=false; fi
    done
    if [[ $safe == true ]]; then
        rm -rf -- "$TEMP"
    else
        echo 'Cleanup refused: instance identity/shutdown unconfirmed; temporary files retained.' >&2
        rc=1
    fi
    exit "$rc"
}
trap cleanup EXIT
# Refuse if any listener exists; fresh datadir, loopback binding and no app startup.
if [[ $(uname -s) == Linux ]]; then
    [[ -z $(ss -H -ltn 'sport = :13379 or sport = :13380') ]] || exit 1
else
    powershell.exe -NoProfile -Command 'if (Get-NetTCPConnection -LocalPort 13379,13380 -State Listen -ErrorAction SilentlyContinue) { exit 1 }' || exit 1
fi
for port in 13379 13380; do
mysqld --no-defaults --initialize-insecure --basedir="$basedir" --datadir="${dirs[$port]}" >"$TEMP/init-$port.log" 2>&1
mysqld --no-defaults --basedir="$basedir" --datadir="${dirs[$port]}" --secure-file-priv=NULL --socket="$win/mysql-$port.sock" --pid-file="$win/mysql-$port.pid" --port="$port" --bind-address=127.0.0.1 --mysqlx=OFF --event-scheduler=OFF --skip-log-bin --console >"$TEMP/server-$port.log" 2>&1 &
pids[$port]=$!
ready=false
for ((i=0;i<60;i++)); do
    if assert_datadir "$port"; then ready=true; break; fi
    kill -0 "${pids[$port]}" 2>/dev/null || break
    sleep 1
done
[[ $ready == true ]]
done
[[ $(sql --execute='SELECT @@server_uuid') != "$(restore_sql --execute='SELECT @@server_uuid')" ]]
# Negative tests: a reachable server with the wrong datadir must receive neither DDL nor shutdown.
expected=${dirs[13380]}
dirs[13380]=${dirs[13379]}
if restore_sql --execute='CREATE DATABASE guard_must_not_exist' 2>/dev/null; then exit 1; fi
if stop_server 13380 2>/dev/null; then exit 1; fi
dirs[13380]=$expected
[[ $(restore_sql --execute="SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='guard_must_not_exist'") == 0 ]]
echo 'PASS datadir mismatch blocks SQL and shutdown; distinct server UUIDs verified'
sql --execute="CREATE DATABASE backup_fixture CHARACTER SET utf8mb4; CREATE TABLE backup_fixture.parent(id INT PRIMARY KEY, value VARCHAR(100), payload BLOB) ENGINE=InnoDB; CREATE TABLE backup_fixture.child(id INT PRIMARY KEY, parent_id INT, FOREIGN KEY(parent_id) REFERENCES parent(id)) ENGINE=InnoDB; INSERT INTO backup_fixture.parent VALUES (1,CONVERT(0xED959CEAB880 USING utf8mb4),0x0001FF),(2,'synthetic',NULL); INSERT INTO backup_fixture.child VALUES(1,1); CREATE VIEW backup_fixture.sample_view AS SELECT id FROM backup_fixture.parent;"
sql backup_fixture --execute="CREATE PROCEDURE fixture_count() SELECT COUNT(*) FROM parent; CREATE TRIGGER fixture_trigger BEFORE INSERT ON child FOR EACH ROW SET NEW.parent_id=COALESCE(NEW.parent_id,1); CREATE EVENT fixture_event ON SCHEDULE EVERY 1 DAY DISABLE DO INSERT INTO parent VALUES(99,'event',NULL);"
sql --execute="CREATE USER 'fixture_backup'@'127.0.0.1' IDENTIFIED BY 'synthetic-only'; GRANT SELECT, SHOW VIEW, TRIGGER, EVENT ON backup_fixture.* TO 'fixture_backup'@'127.0.0.1'; GRANT SHOW_ROUTINE ON *.* TO 'fixture_backup'@'127.0.0.1';"
printf '[client]\nuser=fixture_backup\npassword=synthetic-only\nhost=127.0.0.1\nport=13379\nprotocol=TCP\n' > "$TEMP/client.cnf"
chmod 600 "$TEMP/client.cnf"
if mysql --defaults-file="$TEMP/client.cnf" --execute='CREATE TABLE backup_fixture.forbidden(id INT)' >/dev/null 2>&1; then exit 1; fi
echo 'PASS backup account denies writes'
mkdir "$TEMP/backups"
bash "$ROOT/scripts/mysql-backup.sh" "$TEMP/client.cnf" backup_fixture "$TEMP/backups"
bundle=$(find "$TEMP/backups" -maxdepth 1 -type d -name 'mysql-*')
(cd "$bundle" && sha256sum -c SHA256SUMS)
restore_sql --execute='CREATE DATABASE backup_fixture CHARACTER SET utf8mb4;'
gzip -dc "$bundle/backup.sql.gz" | restore_sql backup_fixture
query='SELECT id,HEX(value),HEX(payload) FROM parent ORDER BY id; SELECT * FROM child ORDER BY id; SELECT * FROM sample_view ORDER BY id;'
sql backup_fixture --execute="$query" > "$TEMP/before"
restore_sql backup_fixture --execute="$query" > "$TEMP/after"
cmp "$TEMP/before" "$TEMP/after"
[[ $(restore_sql backup_fixture --execute='CALL fixture_count()') == 2 ]]
restore_sql backup_fixture --execute='INSERT INTO child VALUES(3,NULL);'
[[ $(restore_sql backup_fixture --execute='SELECT parent_id FROM child WHERE id=3') == 1 ]]
[[ $(restore_sql --execute="SELECT COUNT(*) FROM information_schema.events WHERE event_schema='backup_fixture' AND status='DISABLED'") == 1 ]]
if restore_sql backup_fixture --execute='INSERT INTO child VALUES(2,999)' >/dev/null 2>&1; then exit 1; fi
echo 'PASS actual dump/restore: Unicode, binary, NULL, rows, view, foreign key, routine, trigger, event'
mkdir "$TEMP/backups/.mysql-backup.lock"
if bash "$ROOT/scripts/mysql-backup.sh" "$TEMP/client.cnf" backup_fixture "$TEMP/backups" 2>"$TEMP/failure"; then exit 1; fi
grep -q 'FAILED stage=lock' "$TEMP/failure"
rmdir "$TEMP/backups/.mysql-backup.lock"
if bash "$ROOT/scripts/mysql-backup.sh" "$TEMP/client.cnf" missing_fixture "$TEMP/backups" 2>"$TEMP/failure"; then exit 1; fi
grep -q 'FAILED stage=dump' "$TEMP/failure"
[[ $(find "$TEMP/backups" -maxdepth 1 -type d -name 'mysql-*' | wc -l) == 1 ]]
[[ $(find "$TEMP/backups" -maxdepth 1 -name '.partial.*' | wc -l) == 0 ]]
echo 'PASS lock rejection, dump failure, partial cleanup, previous backup preserved'
sql --execute='CREATE TABLE backup_fixture.unsupported(id INT) ENGINE=MyISAM;'
if bash "$ROOT/scripts/mysql-backup.sh" "$TEMP/client.cnf" backup_fixture "$TEMP/backups" 2>"$TEMP/failure"; then exit 1; fi
grep -q 'FAILED stage=engine-check' "$TEMP/failure"
sql --execute='DROP TABLE backup_fixture.unsupported;'
old="$TEMP/backups/mysql-backup_fixture-20000101T000000Z-123"
mkdir "$old"
cp "$bundle/backup.sql.gz" "$bundle/SHA256SUMS" "$old/"
touch -t 200001010000 "$old"
mkdir "$TEMP/backups/unrelated"
touch -t 200001010000 "$TEMP/backups/unrelated"
RETENTION_DAYS=1 bash "$ROOT/scripts/mysql-backup.sh" "$TEMP/client.cnf" backup_fixture "$TEMP/backups"
[[ ! -e $old && -d $TEMP/backups/unrelated && -d $bundle ]]
printf corrupt >> "$bundle/backup.sql.gz"
if (cd "$bundle" && sha256sum -c SHA256SUMS >/dev/null 2>&1); then exit 1; fi
echo 'PASS non-InnoDB rejection, retention scope, checksum corruption detection'
stop_server 13379
# Recheck stored objects with the source server stopped, including qualified view references.
restore_sql backup_fixture --execute="$query" > "$TEMP/source-off"
[[ $(restore_sql backup_fixture --execute='CALL fixture_count()') == 2 ]]
restore_sql backup_fixture --execute='INSERT INTO child VALUES(4,NULL);'
[[ $(restore_sql backup_fixture --execute='SELECT parent_id FROM child WHERE id=4') == 1 ]]
echo 'PASS restored view, routine and trigger with source instance stopped'
