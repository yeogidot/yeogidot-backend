param(
    [ValidateRange(1024, 65535)][int]$Port = 53317,
    [string]$MySqlBin = 'C:\Program Files\MySQL\MySQL Server 8.0\bin'
)
$ErrorActionPreference = 'Stop'
$claimRepo = Split-Path -Parent $PSScriptRoot
$claimEnvNames = @('RUN_R2_MYSQL_TEST', 'R2_TEST_DB_NAME', 'R2_TEST_DB_PORT',
    'R2_TEST_DB_USERNAME', 'R2_TEST_DB_PASSWORD', 'MYSQL_PWD')
$claimPreviousEnv = @{}
foreach ($name in $claimEnvNames) {
    $claimPreviousEnv[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$claimServer = $null
$claimAuthenticated = $false
$claimMysql = Join-Path $MySqlBin 'mysql.exe'
$claimMysqld = Join-Path $MySqlBin 'mysqld.exe'
$claimMysqladmin = Join-Path $MySqlBin 'mysqladmin.exe'
foreach ($binary in @($claimMysql, $claimMysqld, $claimMysqladmin)) {
    if (!(Test-Path -LiteralPath $binary)) { throw "Missing MySQL binary: $binary" }
}
# Never attach to the installed MySQL service or an already-listening port.
if (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) {
    throw 'Port already in use; choose an unused local port. Existing servers are never reused.'
}
$claimBase = 'yeogidot_r2_test_' + (Get-Date -Format 'yyyyMMdd') + '_' +
    [Guid]::NewGuid().ToString('N').Substring(0, 8)
if ($claimBase -cnotmatch '^yeogidot_r2_test_[0-9]{8}_[a-f0-9]{8}$') { throw 'Invalid test schema base' }
$claimOutputRoot = [IO.Path]::GetFullPath((Join-Path $claimRepo 'output'))
$claimRun = [IO.Path]::GetFullPath((Join-Path $claimOutputRoot $claimBase))
if (!$claimRun.StartsWith($claimOutputRoot + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid output target' }
if (Test-Path -LiteralPath $claimRun) { throw 'Refusing to reuse a data directory' }
$null = New-Item -ItemType Directory -Path $claimRun
$claimData = Join-Path $claimRun 'data'
$claimPassword = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
$claimClientArgs = @('--no-defaults', '--protocol=TCP', '--host=127.0.0.1',
    "--port=$Port", '--user=root', '--connect-timeout=2', '--batch', '--skip-column-names')
Push-Location $claimRepo
try {
    Write-Host "Fresh loopback-only MySQL: 127.0.0.1:$Port; data=$claimData; FAKE R2."
    Write-Host 'No production settings/credentials are loaded. No existing database is modified.'
    $claimInitializer = Start-Process -FilePath $claimMysqld -WindowStyle Hidden -PassThru `
        -ArgumentList @('--no-defaults', '--initialize-insecure', "--datadir=`"$claimData`"") `
        -RedirectStandardOutput (Join-Path $claimRun 'initialize.stdout.log') `
        -RedirectStandardError (Join-Path $claimRun 'initialize.stderr.log')
    # Keep a process handle open so Windows PowerShell can read ExitCode after termination.
    $null = $claimInitializer.Handle
    if (!$claimInitializer.WaitForExit(60000)) {
        $claimInitializer.Kill()
        throw 'Fresh MySQL initialization timed out; retained logs for inspection'
    }
    $claimInitializer.Refresh()
    if ($claimInitializer.ExitCode -ne 0) { throw 'Fresh MySQL initialization failed; see retained logs' }
    $claimServer = Start-Process -FilePath $claimMysqld -WindowStyle Hidden -PassThru `
        -ArgumentList @('--no-defaults', '--no-monitor', "--datadir=`"$claimData`"", '--bind-address=127.0.0.1',
            "--port=$Port", '--mysqlx=OFF', '--skip-log-bin', '--innodb-lock-wait-timeout=10',
            "--log-error=`"$(Join-Path $claimRun 'mysql.log')`"")
    # Clear inherited credentials: these connections are ONLY to the newly created instance.
    [Environment]::SetEnvironmentVariable('MYSQL_PWD', $null, 'Process')
    $claimDeadline = [DateTime]::UtcNow.AddSeconds(45)
    do {
        if ($claimServer.HasExited) { throw 'Temporary MySQL exited; see mysql.log' }
        $claimListener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
        if ($claimListener) {
            if (@($claimListener | Where-Object { $_.OwningProcess -ne $claimServer.Id }).Count -gt 0) {
                throw 'Port is not owned by the newly started process; refusing database access'
            }
            break
        }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $claimDeadline)
    if (!$claimListener) { throw 'Temporary MySQL startup timed out' }
    $claimSql = "CREATE DATABASE ${claimBase}_claim CHARACTER SET utf8mb4; " +
        "CREATE DATABASE ${claimBase}_photo CHARACTER SET utf8mb4; " +
        "CREATE DATABASE ${claimBase}_batch CHARACTER SET utf8mb4; " +
        "ALTER USER 'root'@'localhost' IDENTIFIED BY '$claimPassword';"
    # Generated password travels over stdin, not command arguments or saved files.
    $claimSql | & $claimMysql @claimClientArgs
    if ($LASTEXITCODE -ne 0) { throw 'Fresh schema/account initialization failed' }
    $claimAuthenticated = $true
    $env:MYSQL_PWD = $claimPassword
    & $claimMysql @claimClientArgs '--execute=SELECT VERSION(), @@transaction_isolation;'
    if ($LASTEXITCODE -ne 0) { throw 'Fresh MySQL verification failed' }
    $env:RUN_R2_MYSQL_TEST = 'true'
    $env:R2_TEST_DB_NAME = $claimBase
    $env:R2_TEST_DB_PORT = "$Port"
    $env:R2_TEST_DB_USERNAME = 'root'
    $env:R2_TEST_DB_PASSWORD = $claimPassword
    $claimStarted = Get-Date
    & .\gradlew.bat test --no-daemon --console=plain --rerun-tasks `
        --tests '*R2DeletionClaimConcurrencyMySqlIntegrationTest' `
        --tests '*PhotoDeletionMySqlIntegrationTest' `
        --tests '*R2DeletionBatchMySqlIntegrationTest'
    $claimExit = $LASTEXITCODE
    $claimExpected = @{
        'R2DeletionClaimConcurrencyMySqlIntegrationTest' = 20
        'PhotoDeletionMySqlIntegrationTest' = 15
        'R2DeletionBatchMySqlIntegrationTest' = 2
    }
    $claimReports = Join-Path $claimRun 'reports'
    $null = New-Item -ItemType Directory -Path $claimReports
    foreach ($suiteName in $claimExpected.Keys) {
        $claimXml = Join-Path $claimRepo "build/test-results/test/TEST-com.yeogidot.yeogidot.service.r2.$suiteName.xml"
        if (!(Test-Path -LiteralPath $claimXml) -or (Get-Item -LiteralPath $claimXml).LastWriteTime -lt $claimStarted) {
            throw "Fresh report missing: $suiteName"
        }
        Copy-Item -LiteralPath $claimXml -Destination $claimReports
        [xml]$claimReport = Get-Content -LiteralPath $claimXml -Raw
        $suite = $claimReport.testsuite
        Write-Host "$suiteName tests=$($suite.tests) failures=$($suite.failures) errors=$($suite.errors) skipped=$($suite.skipped)"
        if ([int]$suite.tests -ne $claimExpected[$suiteName] -or [int]$suite.failures -ne 0 -or
            [int]$suite.errors -ne 0 -or [int]$suite.skipped -ne 0) {
            throw "Suite failed/skipped or unexpected count: $suiteName"
        }
    }
    if ($claimExit -ne 0) { throw 'Gradle failed; do not report success' }
    Write-Host 'MySQL: 20 claim-race repetitions + 17 recovery/batch cases passed, no skips.'
} finally {
    try {
        if ($claimServer -and !$claimServer.HasExited) {
            if ($claimAuthenticated) {
                $env:MYSQL_PWD = $claimPassword
                & $claimMysqladmin --no-defaults --protocol=TCP --host=127.0.0.1 "--port=$Port" `
                    --user=root --connect-timeout=2 shutdown
            }
            if (!$claimServer.WaitForExit(10000)) {
                # Only terminate the process started by this script, never the installed service.
                $claimServer.Kill()
                $claimServer.WaitForExit(5000) | Out-Null
            }
        }
    } finally {
        foreach ($name in $claimEnvNames) {
            [Environment]::SetEnvironmentVariable($name, $claimPreviousEnv[$name], 'Process')
        }
        $claimPassword = $null
        $claimSql = $null
        Pop-Location
        if ($claimServer -and !$claimServer.HasExited) {
            Write-Warning "Temporary MySQL may still be running: PID=$($claimServer.Id), port=$Port"
        }
        Write-Host "Data/logs/reports retained at: $claimRun"
    }
}
