#!/usr/bin/env bash
# Credentials and backup directory must live outside the checkout.
set +x
set -Eeuo pipefail
umask 077
unset MYSQL_PWD
export MYSQL_TEST_LOGIN_FILE=/dev/null
stage=configuration
work='' locked=false
log() { printf '%s mysql-backup %s\n' "$(date -u +%FT%TZ)" "$1" >&2; }
finish() {
    local rc=$?
    trap - EXIT
    [[ -z $work ]] || rm -rf -- "$work"
    [[ $locked == false ]] || rmdir -- "$BACKUP_DIR/.mysql-backup.lock"
    if (( rc != 0 )); then log "FAILED stage=$stage exit=$rc"; fi
    exit "$rc"
}
trap finish EXIT
trap 'exit 130' INT
trap 'exit 143' TERM HUP
[[ $# == 3 ]] || { log 'Usage: mysql-backup.sh CLIENT_CNF DATABASE BACKUP_DIR'; exit 2; }
cnf=$1 db=$2 BACKUP_DIR=$3
days=${RETENTION_DAYS:-14}
[[ $db =~ ^[a-zA-Z][a-zA-Z0-9_]*$ && $days =~ ^[1-9][0-9]{0,3}$ ]]
[[ $db != mysql && $db != sys && $db != information_schema && $db != performance_schema ]]
[[ -f $cnf && ! -L $cnf && -d $BACKUP_DIR && ! -L $BACKUP_DIR ]]
cnf=$(cd "$(dirname "$cnf")" && printf '%s/%s' "$PWD" "$(basename "$cnf")")
BACKUP_DIR=$(cd "$BACKUP_DIR" && pwd -P)
# Linux production permissions. Windows ACLs are handled by the synthetic test runner.
if [[ $(uname -s) == Linux ]]; then
    [[ -O $cnf && $(stat -c %a "$cnf") == 600 ]]
    [[ -O $BACKUP_DIR && $(stat -c %a "$BACKUP_DIR") == 700 ]]
fi
for tool in mysql mysqldump gzip sha256sum; do command -v "$tool" >/dev/null; done
stage=lock
mkdir "$BACKUP_DIR/.mysql-backup.lock" 2>/dev/null
locked=true
work=$(mktemp -d "$BACKUP_DIR/.partial.XXXXXXXX")
stage=engine-check
# Suppress raw SQL/client errors: they can contain credentials or data.
count=$(mysql --defaults-file="$cnf" --batch --skip-column-names --execute="SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$db' AND table_type='BASE TABLE' AND engine <> 'InnoDB';" 2>/dev/null)
[[ $count == 0 ]]
stage=dump
mysqldump --defaults-file="$cnf" --single-transaction --quick --skip-lock-tables \
    --no-tablespaces --set-gtid-purged=OFF --routines --events --triggers \
    --hex-blob --default-character-set=utf8mb4 "$db" 2>/dev/null | gzip > "$work/backup.sql.gz"
stage=integrity
gzip -t "$work/backup.sql.gz" 2>/dev/null
(cd "$work" && sha256sum backup.sql.gz > SHA256SUMS)
name="mysql-$db-$(date -u +%Y%m%dT%H%M%SZ)-$$"
stage=publish
mv -- "$work" "$BACKUP_DIR/$name"
work=''
stage=retention
# Only completed bundles belonging to this database; never prune on dump failure.
work=$(mktemp -d "$BACKUP_DIR/.partial.XXXXXXXX")
find "$BACKUP_DIR" -mindepth 1 -maxdepth 1 -type d -name "mysql-$db-*" -mmin "+$((days * 1440))" -print0 > "$work/expired"
while IFS= read -r -d '' old; do
    base=${old##*/}
    [[ $base =~ ^mysql-${db}-[0-9]{8}T[0-9]{6}Z-[0-9]+$ ]] || continue
    [[ -f $old/SHA256SUMS && -f $old/backup.sql.gz ]] || continue
    rm -rf -- "$old"
done < "$work/expired"
log "SUCCESS bundle=$name retention_days=$days"
