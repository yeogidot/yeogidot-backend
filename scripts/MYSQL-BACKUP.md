# MySQL 백업과 복원 검증
> 현재 상태 (2026-11-01 KST): 운영 첫 백업의 로컬 격리 복원과 검증 데이터 삭제 완료.
> mysql-backup.timer는 enabled/active이며 매일 한국 시간 03:30 + 최대 5분 지연으로 실행한다.
> 서버 14일/R2 암호화 사본 30일 보관 및 Healthchecks 성공/실패 신호 연결을 적용했다.
> 아래의 과거 미적용/미검증 문구는 각 확인 시점의 기록이며 최신 결과는 맨 끝을 참고한다.

## 확인 결과 (2026-10-31 KST)

운영 서버에 SSH로 접속해 SELECT와 파일/설정 조회만 수행했다. 계정 생성,
백업 실행, 데이터 변경, 파일 설치, cron/timer 등록은 하지 않았다.

|항목|운영 서버에서 확인한 값|
|---|---|
|MySQL|8.0.46-0ubuntu0.24.04.4, Ubuntu aarch64|
|애플리케이션 DB|yeogidot, 8개 테이블, 모두 InnoDB|
|데이터+인덱스|information_schema 추정 합계 0.42 MiB (덤프 크기/정확한 물리 사용량 아님)|
|루틴/이벤트/트리거|조회 결과 모두 0개|
|GTID / binlog|OFF / ON|
|루트 디스크|45 GiB 중 7.3 GiB 사용, 약 37 GiB 여유|
|백업 파일 검색|/var/backups, /home/ubuntu, /opt, /srv에서 sql/sql.gz/xbstream/sql.zst 파일 0개|
|예약 작업 검색|표준 cron 및 /etc/systemd/system, ubuntu 사용자 systemd에서 MySQL 백업 명령 패턴 일치 0개|

`dpkg-db-backup.timer`는 패키지 관리자 데이터용이며 MySQL 백업이 아니다.
이 검색으로 다른 경로/확장자/도구, 다른 사용자 timer, OCI 외부 스냅샷의 부재까지
보장하지는 않는다. 서버 DB 인증 정보와 사용자 데이터는 출력하지 않았다.

기존 배포의 deploy-backups는 JAR/r2-ops/버전 파일만 보관한다. DB 복구와는 별개다.
prod는 ddl-auto=validate이며 스키마 변경은 수동 SQL로 관리된다.

## 구현과 실행 계약

`scripts/mysql-backup.sh CLIENT_CNF DATABASE BACKUP_DIR`:

- Bash, MySQL 8.0 클라이언트, GNU coreutils/find, gzip 필요.
- 대상 DB 하나를 single-transaction/quick으로 덤프하며 InnoDB 이외 테이블은 거부한다.
- 데이터/테이블/뷰/트리거/루틴/이벤트를 포함한다. 계정/권한, MySQL 서버 설정,
  binlog 파일, Redis 데이터, R2 사진 객체는 포함하지 않는다.
- 인증 파일은 Git 밖에 두고 소유자 권한 600, 백업 디렉터리는 소유자 권한 700을 요구한다.
  Windows의 합성 테스트에서는 POSIX 권한 검사를 생략한다.
- 비밀번호를 인자/환경변수에 넣지 않는다. xtrace와 MYSQL_PWD를 끄고 로그인 파일을 배제한다.
  raw client 오류를 출력하지 않고 UTC 시각/실패 단계/종료 코드만 stderr로 남긴다.
- 임시 디렉터리에 압축 덤프와 SHA256SUMS를 만든 후 gzip 검사를 거쳐 디렉터리를 rename한다.
  체크섬은 손상 감지용이며 암호화나 진위 인증을 대체하지 않는다.
- 보관 기간 기본 14일, RETENTION_DAYS=1..9999로 설정. 성공적으로 새 백업을 만든 뒤에만
  해당 DB의 이름 규칙과 완료 파일을 갖춘 보관 기간 초과 디렉터리를 제거한다.
  기간은 디렉터리 mtime 기준이다. 다른 파일/DB 백업은 삭제하지 않는다.
- mkdir 잠금으로 동시 실행을 거부한다. 정상 종료와 일반 시그널에서는 잠금/임시 파일을 정리한다.
  전원 장애/SIGKILL 후 잠금이 남으면 실행 중 프로세스가 없는지 먼저 확인하고 운영자가 정리한다.
  백업 디렉터리에는 다른 사용자가 쓰지 못하게 한다.
- 덤프 중 DDL(ALTER/CREATE/DROP/RENAME/TRUNCATE)을 실행하지 않는다. 배포 및 수동 마이그레이션과
  백업 시간대를 분리한다. 현재 배포 잠금과 백업 잠금은 서로 연동되지 않는다.
- 단일 일일 덤프만으로는 덤프 시점 이후 데이터를 되살릴 수 없다. binlog가 켜져 있어도
  별도 보관·좌표 기록·복구 훈련 없이는 시점 복구(PITR)를 보장하지 않는다.

공식 옵션/권한 근거: [MySQL 8.0 mysqldump](https://dev.mysql.com/doc/refman/8.0/en/mysqldump.html).

## 서버 적용안 — 아직 적용하지 않음

1. 로그인 불가 OS 계정 `yeogidot-backup`을 생성한다. 실행 스크립트는 root 소유 755로
   `/usr/local/sbin/yeogidot-mysql-backup`에 설치한다.
2. `/etc/yeogidot-backup`과 `/var/backups/yeogidot-mysql`은 해당 계정 소유 700으로 만든다.
   `backup-config/client.cnf.example`을 전자의 `client.cnf`로 복사하여 서버에서만 값을 채운다.
   완성 파일은 해당 계정 소유 600으로 설정한다. 비밀번호를 채팅·셸 이력·Git에 넣지 않는다.
3. MySQL 관리자가 서버 내부에서 아래 계정을 생성한다. 비밀번호 자리표시자는 실제 명령이 아니다.
   관리자의 안전한 비밀 입력 절차를 사용한다.

```sql
CREATE USER 'yeogidot_backup'@'localhost' IDENTIFIED BY '<서버에서만 입력>';
GRANT SELECT, SHOW VIEW, TRIGGER, EVENT ON yeogidot.* TO 'yeogidot_backup'@'localhost';
GRANT SHOW_ROUTINE ON *.* TO 'yeogidot_backup'@'localhost';
```

SHOW_ROUTINE은 루틴 정의를 백업하기 위한 전역 메타데이터 읽기 권한이다.
쓰기 권한이나 PROCESS/LOCK TABLES/RELOAD는 이 설정에서 부여하지 않는다.
현재 객체가 없어도 향후 루틴/이벤트/트리거를 포함하도록 설정했다.
운영 계정으로 첫 덤프 성공과 권한 적합성은 아직 검증하지 않았다.

4. 해당 OS 계정으로 첫 백업을 실행하고 종료 코드 0, SUCCESS, gzip 검사 및 체크섬을 확인한다.
   예상 덤프보다 충분한 여유 공간을 확보한다. 디스크 부족 시 실패하며 이전 완료본은 유지한다.
5. `backup-config/mysql-backup.service`와 `.timer`를 `/etc/systemd/system/`에 설치하고
   daemon-reload 후 timer를 enable --now 한다. 매일 한국 시간 03:30 + 최대 5분 지연,
   서버가 꺼져 있던 예약 실행은 부팅 후 보충한다. OS/MySQL 계정 생성과 설치는 별도 적용 작업이다.

실패는 systemd 비정상 종료와 journal에 기록된다:

```sh
systemctl status mysql-backup.service mysql-backup.timer
journalctl -u mysql-backup.service --since '2 days ago' --no-pager
```

별도 감시에서 서비스 실패, 마지막 SUCCESS 26시간 경과, 디스크 부족, 외부 업로드 실패를
알리도록 연결해야 한다. 현재 알림 채널은 미설정이다. journal 보관은 서버 journald 정책을 따르며,
운영 적용 시 최소 30일 로그 보관을 확인한다. 서비스 실행 제한은 30분이다.

## 서버 밖 보관안 — 업로드 미실행

- 로컬 14일, 외부 일별 30일 + 월별 12개월을 초기 정책으로 제안한다.
- OCI Object Storage의 별도 버킷 또는 사진과 분리한 비공개 R2 백업 버킷을 사용한다.
  같은 서버 디스크에 복사하는 것은 외부 백업이 아니다.
- 완료된 번들을 공개키 기반 도구(예: age)로 암호화한 뒤 업로드한다.
  서버에는 암호화 공개키만, 복호화 개인키는 별도 비밀 저장소/오프라인에 보관한다.
  파일명에는 DB 이름과 시각 정도만 포함하고 개인정보는 넣지 않는다.
- 업로드 자격 증명은 해당 버킷에 필요한 권한만 부여하고 Git/로그 밖에 보관한다.
  사진 삭제 작업용 R2 자격 증명과 분리한다. 지원하는 저장소에서는 보존 잠금과
  별도 관리자 권한으로 백업 삭제를 제한한다.
- 덤프 완료 후 외부 업로드를 별도 단계로 실행하고 성공 여부를 독립 감시한다.
  원격 객체 존재만 확인하지 말고 암호화 파일 해시를 비교한다. 정기적으로 다운로드/복호화/
  체크섬/격리 복원을 끝까지 검증한다. 동기화 삭제 옵션으로 로컬 정리를 원격에 전파하지 않는다.
- 외부 보관 실패가 계속되면 로컬 14일 만료 전에 대응해야 한다. 이 스크립트는 외부 업로드를
  수행하거나 성공을 확인하지 않는다. 버킷/암호화 키/보존 정책 확정 후 별도 적용한다.

## 로컬 실제 검증 — 운영 백업 검증과 구분

Windows Git Bash에서 다음을 실행했다:

```sh
bash scripts/tests/test-mysql-backup.sh
```

설치된 MySQL 8.0.46 바이너리를 사용하되 매번 서로 다른 임시 datadir의 두 서버를 만든다.
원본은 127.0.0.1:13379, 복원 대상은 127.0.0.1:13380이며 server_uuid가 다른지 확인한다.
해당 포트가 사용 중이면 중단한다. 기존 서비스 설정을 읽지 않고
mysqlx/binlog/event scheduler를 끈다. 앱·Redis·R2 연결 없이 합성 데이터만 만든다.
테스트 서버의 root는 임시 무암호 계정이므로 신뢰하는 로컬 개발 PC에서만 실행한다.
모든 테스트 SQL 및 서버 종료 전에 @@datadir를 이번 실행의 예상 경로와 비교한다.
Windows 경로는 raw 출력으로 읽고 구분자·대소문자·마지막 구분자를 정규화한다.
불일치 시 SQL/종료를 거부한다. 종료를 확인하지 못하면 임시 파일을 보존하고 실패 처리한다.
정상 종료 시 두 서버를 종료하고 임시 데이터와 덤프를 제거한다.

2026-11-01 보완 후 실제 실행 결과 모두 통과:

- 실제 mysqldump → gzip → SHA256 → 별도 서버의 동일 이름 스키마 복원.
- 잘못된 예상 datadir로 데이터 생성과 종료를 시도해 모두 거부되고 서버/스키마가 유지되는지 검증.
- 원본 서버 종료 후에도 복원된 뷰/프로시저/트리거가 동작하는지 재확인.
- 한글/바이너리/NULL/행 값 비교, 뷰 조회, 외래 키 위반 거부.
- 프로시저 실행, 트리거 동작, 비활성 이벤트 복원.
- 동시 실행 거부, 존재하지 않는 DB 덤프 실패, 임시 파일 정리, 기존 완료본 유지.
- MyISAM 거부, 만료 백업만 삭제/관련 없는 디렉터리와 최신본 보존, 손상 체크섬 실패.

이 결과는 합성 fixture에 대한 로컬 검증이다. 운영 스키마/데이터 전체 복원,
Linux 파일 권한/서비스 실행, 백업 전용 계정, timer, 외부 저장소 복구는 아직 미검증이다.
운영 데이터를 다운로드하지 않았다.

## 실제 백업 복원 훈련 절차

운영 백업을 로컬로 내려받기 전 사용자에게 고지한다. 보안 저장소와 처리 기간을 정하고,
전용 VM 또는 네트워크가 격리된 새 MySQL 8.0.46 인스턴스에만 복원한다.
운영 인스턴스의 별도 스키마는 격리가 아니다. 덤프는 SQL 실행 코드이므로 일반 운영 접속
클라이언트로 실행하지 않는다. 기존 서비스/운영 포트를 받는 범용 복원 스크립트는 제공하지 않는다.

1. 격리 서버의 UUID/포트/datadir를 기록하고 운영과 다름을 확인한다. event scheduler를 끄고
   앱/삭제 worker/외부 네트워크를 차단한다. 덤프의 DEFINER 객체에 필요한 복원 권한을 검토한다.
2. 복호화 후 SHA256SUMS와 gzip -t를 통과시킨다. 새 빈 DB에 복원한다.
3. 오류 0건, 테이블/인덱스/외래 키/뷰/루틴/트리거/이벤트 정의와 데이터 제약을 확인한다.
   쓰기가 계속된 운영 DB의 현재 행 수와 과거 덤프 행 수를 단순 비교하지 않는다.
4. 필요한 경우 동일 애플리케이션 버전의 ddl-auto=validate를 격리 환경에서 실행한다.
   R2 삭제 worker와 외부 서비스가 작동하지 않게 설정한 뒤 기능 점검한다.
5. 복원 소요 시간과 백업 시각을 기록해 RTO/RPO를 평가한다. 데이터나 SQL 원문 대신
   건수/통과 여부만 보고한다. 검증 데이터를 정해진 정책에 따라 폐기한다.

R2 사진은 MySQL의 사진 경로/메타데이터와 별개다. DB를 과거로 복원해도 이미 삭제된
R2 객체는 되살아나지 않는다. 사진 파일은 별도 버킷/계정으로 복제·보관해야 하며,
DB 시점과 객체 보존 기간을 맞춰야 한다. 과거 삭제 작업 테이블을 복원하면 재시도 작업이
다시 실행될 수 있으므로 R2 worker를 끈 상태에서 객체 정합성을 먼저 확인한다.

## Linux 추가 검증 (2026-11-01 KST)

운영 서버가 아닌 로컬 WSL Ubuntu 24.04에서 MySQL 8.0.46을 사용했다.
mysql-server-core-8.0/mysql-client-8.0을 설치했으며 운영 MySQL 서비스는 설치하지 않았다.
테스트 스크립트를 Linux와 Windows Git Bash 공용으로 보완했다.

- 서로 다른 datadir/포트(13379, 13380)/UUID의 합성 데이터 인스턴스로 실제 백업·복원 통과.
- SELECT, SHOW VIEW, TRIGGER, EVENT, SHOW_ROUTINE만 부여한 테스트 DB 계정으로 덤프 성공.
- 해당 계정의 CREATE TABLE 거부 확인. 인증 파일 600, 백업 디렉터리 700 검사 통과.
- 원본 종료 후 복원본의 뷰/프로시저/트리거 동작 확인.
- datadir 불일치 시 SQL/종료 차단, 실패 시 기존 백업 유지, 보관 정책, 손상 체크섬 검사 통과.
- Bash 문법 검사 통과. systemd-analyze calendar로 Asia/Seoul 매일 03:30 해석 확인.
- Linux 서버 바이너리의 기본 secure-file-priv 경로 의존성을 피하도록 테스트에서 NULL로 지정.
  테스트 인스턴스의 파일 import/export를 비활성화하는 설정이며 운영 설정은 변경하지 않는다.

실행: 로컬 Ubuntu의 일반 사용자로 저장소에서 `bash scripts/tests/test-mysql-backup.sh`.
테스트용 DB 자격 증명은 합성 fixture 전용이며 임시 인스턴스 종료 후 폐기된다.

한계: Linux에서 테스트를 실행한 OS 사용자는 기존 로컬 사용자다. 별도 OS 계정,
운영 SOCKET 인증, systemd 서비스의 sandbox 설정을 적용한 실제 실행은 아직 미검증이다.
Windows에서는 이전 테스트 실행 기록만 있으며 이번 공용화 변경 후 재실행하지 않았다.
운영 서버 접속/설치/백업, 실제 운영 데이터 복원, timer 활성화, 외부 업로드는 수행하지 않았다.
첫 Linux 시도의 서버 시작 실패 때는 안전상 임시 디렉터리를 보존했다.
성공한 재실행의 두 인스턴스는 테스트 종료 시 종료되고 해당 임시 파일은 정리된다.

## 운영 적용 순서와 되돌리기

적용은 아직 하지 않는다. 위 서버 적용안의 OS/DB 계정과 파일을 준비한 뒤
먼저 서비스 수동 실행으로 권한/접속/무결성을 확인하고, 실제 백업의 격리 복원을 확인한 후
마지막으로 timer를 활성화한다. 외부 암호화 보관은 별도 단계다.

적용 중 문제가 생기면 timer를 먼저 disable --now 한다. 실행 중인 백업 서비스가 있으면
중지하고 로그를 확인한다. 새로 만든 서비스/timer 파일만 제거한 뒤 daemon-reload한다.
새로 생성한 전용 계정인지 확인하고 다른 작업에서 사용하지 않는 경우에만 DB 계정과
OS 계정을 제거한다. 인증 파일은 별도로 제거하되 완료된 백업은 자동 삭제하지 않는다.
앱 설정, 기존 DB/테이블, 기존 MySQL 서비스는 되돌리기 대상이 아니다.
## 전용 OS 계정·systemd 실제 검증 (2026-11-01 KST)

새 테스트 `scripts/tests/test-mysql-backup-systemd.sh`를 로컬 WSL Ubuntu 24.04에서
root로 실행했다. 위 Linux 추가 검증에서 남았던 전용 OS 계정/Unix socket/systemd 실행을
이번 테스트 범위에서 확인했다. 운영 서버에서는 실행하지 않았다.

```sh
sudo bash scripts/tests/test-mysql-backup-systemd.sh
```

테스트는 /run 아래에 전용 임시 디렉터리, 로그인 불가 임시 OS 계정, /run/systemd/system 아래
고유한 이름의 service/timer를 만든다. MySQL 8.0.46은 새 datadir과 Unix socket만 사용하며
TCP 연결, event scheduler, binlog를 끈다. DB에는 합성 데이터만 넣는다.
운영용 서비스의 User/Group/실행 경로/백업 경로만 테스트 값으로 치환하고
NoNewPrivileges/PrivateTmp/ProtectSystem/ProtectHome/ReadWritePaths는 유지한다.

확인 결과:
- 최소 읽기 권한 DB 계정과 SOCKET 인증으로 전용 OS 계정의 systemd 백업 성공.
- 생성한 백업 디렉터리 700, 파일 600, 소유자 및 gzip/SHA256 검사 통과.
- 인증 파일을 640으로 바꾸면 실패하고 journal에 stage=configuration 기록.
- 테스트 DB 계정을 잠그면 실패하고 journal에 stage=engine-check 기록.
- 실패한 경우 기존 완료 백업 유지, 서비스 Result=exit-code 확인.
- 운영용 매일 03:30 Asia/Seoul timer의 구문/로딩/다음 실행 시각 확인.
- 별도의 테스트용 1초 timer가 서비스를 호출해 새 백업을 생성하는 것 확인.
  실제 일일 예약 시각이나 재부팅 후 Persistent 동작까지 기다려 검증한 것은 아니다.
- 서비스 journal에 합성 비밀번호가 출력되지 않았음.
- 종료 시 테스트 서비스/timer/계정/파일과 MySQL 정리, 잔여 프로세스/예약 없음 확인.

전체 테스트 종료 코드 0. SQL과 종료 명령 전 datadir를 대조하며, 종료 확인 실패 시
임시 데이터와 계정을 보존하고 실패로 끝낸다. Linux 전용 root 테스트이므로 일반 CI의
자동 실행에 추가하지 않았다. 기존 Windows/Linux 백업·복원 테스트와 별도다.

남은 범위: 운영 aarch64 서버에서 전용 계정 권한/접속/최초 백업 확인, 실제 운영 백업의
격리 복원, 정기 실행 활성화, 서버 밖 암호화 보관. 이번 로컬 검증은 운영 적용을 뜻하지 않는다.

## 운영 첫 백업 적용 (2026-11-01 KST)

사용자의 운영 적용 요청에 따라 Oracle Cloud Ubuntu aarch64 서버에 다음을 설치했다.
- 로그인 불가 OS 계정 yeogidot-backup 및 DB 계정 yeogidot_backup@localhost.
- DB 권한: yeogidot.*의 SELECT/SHOW VIEW/TRIGGER/EVENT와 전역 SHOW_ROUTINE.
- 인증 정보는 서버 내부에서 무작위 생성, /etc/yeogidot-backup/client.cnf에만 저장.
  디렉터리 700, 파일 600 및 전용 계정 소유. 비밀번호를 로그/인자/Git에 남기지 않았다.
- root 소유 755 /usr/local/sbin/yeogidot-mysql-backup, systemd service/timer 파일 설치.
- 서비스 수동 실행 성공: Result=success, ExecMainStatus=0.
- 최초 완료 번들: /var/backups/yeogidot-mysql/mysql-yeogidot-20261101T052112Z-2436117.
  압축 덤프 28,808 bytes, gzip 검사 및 SHA256SUMS 일치, 파일 권한 600 확인.
- 설치 전후 /health/ready HTTP 200 확인. 앱 재시작이나 운영 테이블 변경/복원은 하지 않았다.
  DB/OS의 백업 전용 계정과 파일만 추가했다.

실제 운영 백업을 로컬 WSL로 전송하려던 작업은 자동 승인 검토에서
민감 데이터의 payload/목적지에 대한 명시적 승인이 부족하다는 이유로 차단되었다.
전송되지 않았고 운영 백업은 서버 내부에만 있다. 운영 복원 검증 전이므로
mysql-backup.timer는 disabled/inactive로 유지한다. 현재 자동 일일 백업은 동작하지 않는다.

다음 단계는 사용자 승인을 받은 격리 환경에 해당 백업만 전송하여 복원하고
검증 데이터를 정리한 뒤 timer를 활성화하는 것이다. 외부 암호화 장기 보관과
실패 알림은 아직 별도 작업이다.

## 실제 운영 백업 복원 및 자동 백업 활성화 (2026-11-01 KST)

사용자가 실제 운영 백업을 로컬 Ubuntu로 전송·복원·삭제하고, 통과 시 자동 백업을
활성화하도록 명시적으로 승인했다. 앞선 전송 차단 이후 승인을 받아 진행한 결과다.

- 검증 대상: mysql-yeogidot-20261101T052112Z-2436117의 backup.sql.gz와 SHA256SUMS만 전송.
- SSH 스트림으로 WSL의 root 전용 임시 파일에 저장. Windows 파일/Git에는 백업을 저장하지 않음.
- 로컬 MySQL 8.0.46에 새 datadir/Unix socket/전용 OS 계정을 만들고 실제 SQL 복원 성공.
  TCP 네트워크와 event scheduler/binlog를 끄고 앱/Redis/R2/삭제 작업자는 실행하지 않음.
- 복원 전 SHA256/gzip 검사 통과. SQL 복원 종료 코드 0, 테이블 8개/전체 595행 확인.
- CHECK TABLE 통과. 외래 키 7개에 대해 연결되지 않는 참조 행 0건 확인.
- SQL import 약 1.00초. 초기화/전송/검증/종료 시간은 포함하지 않으므로 운영 전체 RTO가 아님.
- 복원한 행 수는 백업 시점의 로컬 복원 결과이며 이후 변할 수 있는 운영 현재 행 수와 비교하지 않음.
- 검증 후 로컬 MySQL을 종료하고 전송 백업/복원 데이터/로그/임시 계정을 삭제.
  잔여 mysqld, 해당 임시 디렉터리, 해당 임시 계정 없음 확인.
- 이 검증은 DB 복원과 테이블/참조 검사 범위다. 앱 전체 기능 또는 R2 객체 복원 검증은 아님.

통과 후 운영 mysql-backup.timer를 enable --now 했고 enabled/active 상태를 확인했다.
다음 예정 실행은 2026-11-02 03:33:44 KST(2026-11-01 18:33:44 UTC)로 표시되었다.
매일 03:30 KST 예약에 RandomizedDelaySec=5min 및 systemd 정확도 허용 오차가 적용된다.
Persistent=yes이며 실제 일일 실행 완료는 첫 예약 시각이 지난 뒤 확인해야 한다.
서비스 직전 수동 실행 결과는 success/0이며 앱 /health/ready HTTP 200을 확인했다.
자동 실패 알림과 서버 밖 장기 보관, R2 사진 별도 백업은 아직 적용하지 않았다.
커밋/푸시는 수행하지 않았다.

## R2 암호화 외부 보관 적용 (2026-11-01 KST)

별도 백업 버킷 yeogidot-db-backups와 버킷 전용 Object Read & Write 키를 사용한다.
키는 사용자가 운영 /etc/yeogidot-backup/r2.env(root:root 600)에 직접 입력했고 값은 출력하지 않았다.
서버에서 파일 권한/필수 값/주소 형식/대상 버킷을 확인했다. 버킷은 비공개로 생성하도록
안내했으며 공개 접근 설정 자체를 관리 API로 조회한 것은 아니다. 저장 등급은 Standard다.

구현 파일:
- scripts/mysql-backup-r2.py: 완료 번들의 로컬 SHA256 검사, age 암호화, R2 업로드,
  전체 원격 암호문을 다시 읽어 SHA256 비교, 검증된 사본 기록, 전용 경로의 오래된 사본 정리.
- scripts/backup-config/mysql-backup-upload.service: 별도 업로드 서비스.
- scripts/backup-config/mysql-backup-r2.conf: /etc/systemd/system/mysql-backup.service.d/r2.conf로
  설치하여 원래 백업 서비스가 성공하면 OnSuccess로 업로드를 실행한다.
- scripts/backup-config/r2.env.example: 비밀이 없는 설정 예시.
- scripts/tests/test-mysql-backup-r2.py: 실제 age + 메모리 S3 검증 4건.

암호화 키:
- 개인키: 사용자 PC C:\Users\jinwon\.yeogidot-backup-keys\age.key.
  폴더 상속 ACL을 제거하고 현재 Windows 사용자만 접근하도록 설정했다.
- 공개키: 같은 로컬 폴더의 age-recipient.txt 및 운영 /etc/yeogidot-backup/age-recipient.txt.
- 서버에는 복호화 개인키를 설치하지 않았다. 개인키 파일은 Git/로그에 포함하지 않았다.
  PC 손실에 대비해 사용자가 개인키를 별도의 안전한 장소에도 보관해야 한다.
  개인키를 잃으면 서버 공개키만으로 암호화 백업을 복구할 수 없다.

흐름: 기존 새벽 DB 백업 성공 -> 별도 업로드 서비스 -> 로컬 보관 중인 번들 확인 ->
age로 backup.sql.gz/SHA256SUMS를 묶은 암호화 tar 생성 -> R2 mysql/age-v1/ 경로 업로드 ->
전체 다운로드 해시 확인 -> .r2-uploaded.json 기록. 기록이 있어도 원격 사본을 다시 검증하고
없거나 손상됐다면 로컬 완료본으로 재업로드한다. 부분/검증 실패 업로드는 성공 기록을 남기지 않는다.
업로드 실패는 별도 서비스 실패로 남으며 로컬 DB 백업 성공과 구분한다.

R2 정리는 모든 로컬 보관 번들의 원격 검증이 끝난 경우에만 한다. 전용 경로의
정해진 파일명 형식과 백업 시각/원격 LastModified 모두 30일이 지난 객체만 삭제한다.
따라서 최근 업로드된 과거 백업은 즉시 지우지 않는다. 사진 버킷/다른 경로/로컬 정리를
원격 sync 삭제로 연결하지 않는다. 실시간 한도에 따른 저장소 과금은 계정 전체 사용량을 따른다.

검증:
- 로컬 테스트 4건 통과: 실제 암호화/복호화, 원격 사본 소실 복구,
  손상된 로컬 파일 업로드 거부, 원격 해시 불일치 시 성공 기록 없음, 보관 정리 범위.
- 운영 첫 번들 업로드 및 전체 암호문 SHA256 일치 확인.
- 실제 R2 사본을 SSH 스트림으로 로컬에 내려받아 PC 개인키로 복호화,
  새 Unix socket 전용 MySQL에서 테이블 8개/595행/외래 키 7개(끊긴 참조 0건) 복원 확인.
  SQL import 약 0.74초이며 전체 복구 RTO를 뜻하지 않는다.
- 검증 후 로컬 사본/복원 DB/임시 OS 계정/서버 종료 및 삭제 완료.
- OnSuccess 연결 후 새 운영 백업을 수동 실행해 업로드 서비스 자동 실행과
  두 번들의 원격 검증/기록 생성까지 확인. timer enabled/active, 앱 HTTP 200.
- 실제 30일 경과 후 정리와 다음 일일 예약 실행은 아직 관찰하지 않았다.
- 운영 의존성: age, python3-boto3. 앱 코드/배포 워크플로는 변경하지 않았다.

조회 명령 (설정 파일 내용이나 환경변수는 출력하지 않는다):
```sh
sudo systemctl show mysql-backup.service mysql-backup-upload.service -p Result -p ExecMainStatus
sudo journalctl -u mysql-backup-upload.service -n 20 --no-pager
systemctl list-timers mysql-backup.timer
```

외부 업로드만 중단하려면 r2.conf 연결을 제거하고 daemon-reload하며 실행 중 업로드
서비스를 중지한다. 기존 DB 백업 timer는 유지할 수 있다. 완료된 R2 객체/개인키를 삭제하지 않는다.
서버 전체 고장 시 DB 복구에는 외부 암호문과 PC의 개인키가 필요하다.
R2 사진 파일 자체의 백업 및 백업/업로드 실패 알림은 아직 별도 작업이다.

## Healthchecks 이메일 알림 연결 (2026-11-01 KST)

사용자가 Gmail 이메일 알림을 요청하고 Healthchecks Check와 이메일 연결을 준비한 뒤,
운영 /etc/yeogidot-backup/healthchecks.env(root:root 600)에 Ping URL을 직접 저장했다.
URL은 출력하거나 Git에 포함하지 않았으며 도메인/UUID 형식과 파일 권한만 확인했다.

구현과 운영 설치:
- scripts/mysql-backup-notify.py -> /usr/local/sbin/yeogidot-mysql-backup-notify.
- mysql-backup-notify@.service: 전용 OS 계정, root 전용 EnvironmentFile, 최대 30초 실행.
- mysql-backup-monitor.conf -> mysql-backup.service.d/monitor.conf:
  DB 백업 OnFailure는 notify@fail, 기존 OnSuccess R2 업로드 연결 유지.
- mysql-backup-upload-monitor.conf -> mysql-backup-upload.service.d/monitor.conf:
  R2 업로드 OnFailure는 notify@fail, 업로드 전체 성공 OnSuccess만 notify@success.
- healthchecks.env.example는 자리표시자만 포함하는 설정 예시다.

전송은 Healthchecks 기본 URL(성공) 또는 /fail(실패)에 고정된 상태 설명만 POST한다.
SQL/사용자 데이터/원본 로그/키/파일명은 보내지 않는다. HTTPS hc-ping.com의 UUID 경로만
허용하고 리디렉션은 거부한다. 요청당 5초, 최대 3회 시도하며 성공은 HTTP 200과
응답 본문 OK를 모두 확인한다. 200이라도 not found/rate limited 응답이면 성공으로 보지 않는다.
전송 실패 로그에는 예외 종류만 남기며 URL이나 응답 원문을 출력하지 않는다.

검증 결과:
- tests/test-mysql-backup-notify.py 5건 통과(경로/고정 본문, 일시 오류 재시도,
  유효하지 않은 Check 응답 거부, URL/모드 제한, 리디렉션 거부).
- tests/test-mysql-backup-monitor-systemd.sh: 로컬 systemd의 모의 작업으로
  정상 완료 success / DB 실패 fail 및 업로드 미실행 / 업로드 실패 fail을 확인.
  실제 DB/Healthchecks/Gmail에 실패를 발생시키는 테스트가 아니다.
- 운영 서비스 연결 후 정상 DB 백업과 R2 업로드를 실행하고
  notify@success Result=success, HEALTHCHECKS_SENT outcome=success 확인.
- DB/업로드/알림 세 단계 모두 success, 기존 timer enabled/active, 앱 HTTP 200.
- Gmail로 실제 실패 메일을 보내는 시험과 사용자 받은편지함 수신 확인은 하지 않았다.
  이메일 주소 인증과 해당 Check의 Email integration 활성화는 Healthchecks에서 관리한다.

Healthchecks Period=1 day, Grace=1 hour 기준 마지막 성공 이후 25시간 동안 신호가 없으면
누락으로 감지한다. 서버 다운/예약 미실행/알림 자체의 네트워크 실패 시에도 이후 성공 신호가
없으면 외부 서비스가 감지한다. 실제 25시간 경과 및 이메일 전달 시간은 직접 시험하지 않았다.
명시적 /fail 신호는 주기 만료를 기다리지 않고 실패를 알린다.
https://healthchecks.io/docs/signaling_failures/
https://healthchecks.io/docs/http_api/

연결을 철회하려면 두 서비스의 monitor.conf 연결만 제거하고 daemon-reload한다.
기존 DB 백업/R2 업로드 연결은 유지한다. 계정/환경 파일을 출력하는 systemctl show 전체 조회나
비밀 URL을 인자에 넣는 curl 명령은 사용하지 않는다.
