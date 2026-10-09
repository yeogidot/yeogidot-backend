param()
$ErrorActionPreference = 'Stop'
$recoveryRepo = Split-Path -Parent $PSScriptRoot
$recoveryEnvNames = @('RUN_R2_MYSQL_TEST', 'R2_TEST_DB_NAME', 'R2_TEST_DB_PORT',
    'R2_TEST_DB_USERNAME', 'R2_TEST_DB_PASSWORD', 'MYSQL_PWD')
$recoveryPreviousEnv = @{}
foreach ($name in $recoveryEnvNames) {
    $recoveryPreviousEnv[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
function Read-RecoverySecret([string]$Label) {
    $secret = Read-Host $Label -AsSecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer); $secret.Dispose() }
}
Push-Location $recoveryRepo
try {
    Write-Host 'LOCAL MYSQL ONLY: 127.0.0.1. FAKE R2. No production settings loaded.'
    Write-Host 'Creates a NEW dedicated database. Tests mutate only that database; retain it for inspection.'
    $recoveryPort = Read-Host 'Local MySQL port [3306]'
    if ([string]::IsNullOrWhiteSpace($recoveryPort)) { $recoveryPort = '3306' }
    if ($recoveryPort -notmatch '^[0-9]{1,5}$' -or [int]$recoveryPort -lt 1 -or [int]$recoveryPort -gt 65535) {
        throw 'Invalid local port'
    }
    $env:R2_TEST_DB_PORT = $recoveryPort
    $env:R2_TEST_DB_USERNAME = Read-Host 'Local MySQL user (must be able to create a new test database)'
    $env:R2_TEST_DB_PASSWORD = Read-RecoverySecret 'Local MySQL password (hidden)'
    if ([string]::IsNullOrWhiteSpace($env:R2_TEST_DB_USERNAME) -or
        [string]::IsNullOrWhiteSpace($env:R2_TEST_DB_PASSWORD)) { throw 'Missing local credentials' }
    $recoveryBase = 'yeogidot_r2_test_' + (Get-Date -Format 'yyyyMMdd') + '_' +
        [Guid]::NewGuid().ToString('N').Substring(0, 8)
    if ($recoveryBase -cnotmatch '^yeogidot_r2_test_[0-9]{8}_[a-f0-9]{8}$') { throw 'Invalid schema' }
    $recoverySchema = $recoveryBase + '_photo'
    Write-Host "Target: 127.0.0.1:$recoveryPort/$recoverySchema"
    if ((Read-Host 'Type RUN to create the fresh database and run failure/recovery tests') -cne 'RUN') {
        throw 'Cancelled'
    }
    $recoveryMysqlCommand = Get-Command mysql.exe -ErrorAction SilentlyContinue
    $recoveryMysql = if ($recoveryMysqlCommand) { $recoveryMysqlCommand.Source }
        else { 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe' }
    if (!(Test-Path -LiteralPath $recoveryMysql)) { throw 'mysql.exe not found' }
    $env:MYSQL_PWD = $env:R2_TEST_DB_PASSWORD
    & $recoveryMysql --no-defaults --protocol=TCP --host=127.0.0.1 "--port=$recoveryPort" `
        "--user=$env:R2_TEST_DB_USERNAME" "--execute=CREATE DATABASE $recoverySchema CHARACTER SET utf8mb4;"
    if ($LASTEXITCODE -ne 0) { throw 'Fresh database creation failed' }
    [Environment]::SetEnvironmentVariable('MYSQL_PWD', $recoveryPreviousEnv['MYSQL_PWD'], 'Process')
    $env:R2_TEST_DB_NAME = $recoveryBase
    $env:RUN_R2_MYSQL_TEST = 'true'
    $recoveryStarted = Get-Date
    & .\gradlew.bat test --no-daemon --console=plain --rerun-tasks --tests '*R2DeletionOperationsMySqlIntegrationTest'
    $recoveryExit = $LASTEXITCODE
    Write-Host "Test DB retained: $recoverySchema"
    if ($recoveryExit -ne 0) { throw 'Tests failed. Do not report success.' }
    $recoveryXml = Join-Path $recoveryRepo 'build/test-results/test/TEST-com.yeogidot.yeogidot.service.R2DeletionOperationsMySqlIntegrationTest.xml'
    if (!(Test-Path -LiteralPath $recoveryXml) -or (Get-Item -LiteralPath $recoveryXml).LastWriteTime -lt $recoveryStarted) {
        throw 'Fresh MySQL test report not found'
    }
    [xml]$recoveryReport = Get-Content -LiteralPath $recoveryXml -Raw
    $recoverySuite = $recoveryReport.testsuite
    if ([int]$recoverySuite.tests -ne 13 -or [int]$recoverySuite.skipped -ne 0 -or
        [int]$recoverySuite.failures -ne 0 -or [int]$recoverySuite.errors -ne 0) {
        throw 'MySQL tests skipped or failed'
    }
    Write-Host "MySQL recovery tests: $($recoverySuite.tests) passed, 0 failed, 0 skipped."
    Write-Host "Report: $recoveryXml"
} finally {
    foreach ($name in $recoveryEnvNames) {
        [Environment]::SetEnvironmentVariable($name, $recoveryPreviousEnv[$name], 'Process')
    }
    Pop-Location
}
