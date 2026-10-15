#!/usr/bin/env bash
# 가짜 Java 실행기로 인자/환경 격리만 확인한다. DB/R2/배포에는 접근하지 않는다.
set -euo pipefail
source_script=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)/r2-ops
test_dir=$(mktemp -d)
trap 'rm -f -- "$test_dir/app dir/yeogidot.jar" "$test_dir/app dir/r2-ops" "$test_dir/java" "$test_dir/output"; rmdir -- "$test_dir/app dir" "$test_dir"' EXIT
mkdir "$test_dir/app dir"
cp -- "$source_script" "$test_dir/app dir/r2-ops"
touch "$test_dir/app dir/yeogidot.jar"
printf '%s\n' '#!/usr/bin/env bash' 'set -euo pipefail' \
  '[[ -z ${R2_OPS_DB_PASSWORD+x} && -z ${R2_OPS_ALLOW_WRITE+x} && -z ${R2_OPS_CONFIRM+x} ]]' \
  '[[ -z ${JAVA_TOOL_OPTIONS+x} && -z ${JDK_JAVA_OPTIONS+x} && -z ${LOADER_MAIN+x} ]]' \
  '[[ -z ${AWS_SECRET_ACCESS_KEY+x} && -z ${BASH_ENV+x} ]]' \
  '[[ ! -f loader.properties ]]' \
  '[[ $1 == -Xmx128m && $2 == -Dfile.encoding=UTF-8 ]]' \
  '[[ $3 == -Dstdout.encoding=UTF-8 && $4 == -Dstderr.encoding=UTF-8 ]]' \
  '[[ $5 == -Dloader.main=com.yeogidot.yeogidot.operations.R2DeletionOperationsConsole ]]' \
  '[[ $6 == -cp && $7 == */app\ dir/yeogidot.jar ]]' \
  '[[ $8 == org.springframework.boot.loader.launch.PropertiesLauncher ]]' \
  '[[ $9 == */app\ dir ]]' \
  '[[ $# == 9 || ( $# == 10 && ${10} == --prompt ) ]]' \
  'printf "ISOLATED_LAUNCH_OK\n"' > "$test_dir/java"
chmod 700 "$test_dir/java"

bash "$source_script" --help > "$test_dir/output"
grep -q 'Usage:' "$test_dir/output"
if bash "$source_script" unexpected > "$test_dir/output" 2>&1; then
  printf '%s\n' 'Unexpected arguments accepted' >&2; exit 1
fi
if bash "$source_script" --prompt extra > "$test_dir/output" 2>&1; then
  printf '%s\n' 'Extra arguments accepted' >&2; exit 1
fi
for mode in '' --prompt; do
  PATH="$test_dir:$PATH" R2_OPS_DB_PASSWORD=do-not-inherit R2_OPS_ALLOW_WRITE=true \
    R2_OPS_CONFIRM=unsafe JAVA_TOOL_OPTIONS=unsafe JDK_JAVA_OPTIONS=unsafe \
    LOADER_MAIN=unsafe AWS_SECRET_ACCESS_KEY=unsafe \
    bash "$test_dir/app dir/r2-ops" ${mode:+"$mode"} > "$test_dir/output"
  grep -q '^ISOLATED_LAUNCH_OK$' "$test_dir/output"
done
printf '%s\n' 'R2 wrapper checks passed (fake Java only; no DB/R2).'
