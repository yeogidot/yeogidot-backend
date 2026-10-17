param()
$ErrorActionPreference = 'Stop'
$manualRecoveryRepo = Split-Path -Parent $PSScriptRoot
$manualRecoveryNames = @('RUN_R2_MANUAL_LIVE_TEST', 'RUN_R2_LIVE_TEST', 'RUN_R2_MYSQL_TEST',
    'R2_LIVE_BUCKET', 'R2_LIVE_ENDPOINT', 'R2_LIVE_ACCESS_KEY_ID', 'R2_LIVE_SECRET_ACCESS_KEY',
    'R2_TEST_DB_NAME', 'R2_TEST_DB_PORT', 'R2_TEST_DB_USERNAME', 'R2_TEST_DB_PASSWORD', 'MYSQL_PWD')
$manualRecoveryPrevious = @{}
foreach ($name in $manualRecoveryNames) {
    $manualRecoveryPrevious[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
function Read-ManualRecoverySecret([string]$Label) {
    $secret = Read-Host $Label -AsSecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer); $secret.Dispose() }
}
Push-Location $manualRecoveryRepo
try {
    Write-Host 'LOCAL TEST DB + TEST R2 ONLY: 127.0.0.1, bucket=yeogidot-r2-delete-test'
    Write-Host 'Creates a NEW test database and ONE tiny temporary object. No production settings are loaded.'
    Write-Host 'Use keys restricted ONLY to this test bucket. Never enter a production R2 token.'
    Write-Host 'Builds a local bootJar and runs its real CLI; no push, deployment or server restart.'
    $manualRecoveryEndpoint = (Read-Host 'S3 account endpoint (https://ACCOUNT_ID.r2.cloudflarestorage.com)').Trim()
    if ($manualRecoveryEndpoint -cnotmatch '^https://[a-f0-9]{32}\.r2\.cloudflarestorage\.com/?$') {
        throw 'Invalid HTTPS account endpoint; no bucket name, path or query permitted'
    }
    $env:R2_LIVE_ENDPOINT = $manualRecoveryEndpoint
    $env:R2_LIVE_BUCKET = 'yeogidot-r2-delete-test'
    $env:R2_LIVE_ACCESS_KEY_ID = Read-ManualRecoverySecret 'Test-only Access Key ID (hidden)'
    $env:R2_LIVE_SECRET_ACCESS_KEY = Read-ManualRecoverySecret 'Test-only Secret Access Key (hidden)'
    $manualRecoveryPort = Read-Host 'Local Windows MySQL port [3306]'
    if ([string]::IsNullOrWhiteSpace($manualRecoveryPort)) { $manualRecoveryPort = '3306' }
    if ($manualRecoveryPort -notmatch '^[0-9]{1,5}$' -or [int]$manualRecoveryPort -lt 1 -or [int]$manualRecoveryPort -gt 65535) {
        throw 'Invalid local port'
    }
    $env:R2_TEST_DB_PORT = $manualRecoveryPort
    $env:R2_TEST_DB_USERNAME = Read-Host 'Local MySQL user (must be able to create a new test database)'
    $env:R2_TEST_DB_PASSWORD = Read-ManualRecoverySecret 'Local MySQL password (hidden)'
    foreach ($name in @('R2_LIVE_ACCESS_KEY_ID', 'R2_LIVE_SECRET_ACCESS_KEY', 'R2_TEST_DB_USERNAME', 'R2_TEST_DB_PASSWORD')) {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name, 'Process'))) {
            throw "Missing $name"
        }
    }
    $manualRecoveryBase = 'yeogidot_r2_test_' + (Get-Date -Format 'yyyyMMdd') + '_' +
        [Guid]::NewGuid().ToString('N').Substring(0, 8)
    if ($manualRecoveryBase -cnotmatch '^yeogidot_r2_test_[0-9]{8}_[a-f0-9]{8}$') { throw 'Invalid generated schema' }
    $manualRecoverySchema = $manualRecoveryBase + '_photo'
    Write-Host "Target: 127.0.0.1:$manualRecoveryPort/$manualRecoverySchema"
    if ((Read-Host 'Type RUN to create the test DB and verify manual recovery of ONE test R2 object') -cne 'RUN') {
        throw 'Cancelled'
    }
    $manualRecoveryMysqlCommand = Get-Command mysql.exe -ErrorAction SilentlyContinue
    $manualRecoveryMysql = if ($manualRecoveryMysqlCommand) { $manualRecoveryMysqlCommand.Source }
        else { 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe' }
    if (!(Test-Path -LiteralPath $manualRecoveryMysql)) { throw 'mysql.exe not found' }
    $env:MYSQL_PWD = $env:R2_TEST_DB_PASSWORD
    & $manualRecoveryMysql --no-defaults --protocol=TCP --host=127.0.0.1 "--port=$manualRecoveryPort" `
        "--user=$env:R2_TEST_DB_USERNAME" "--execute=CREATE DATABASE $manualRecoverySchema CHARACTER SET utf8mb4;"
    if ($LASTEXITCODE -ne 0) { throw 'Fresh test database creation failed' }
    [Environment]::SetEnvironmentVariable('MYSQL_PWD', $manualRecoveryPrevious['MYSQL_PWD'], 'Process')
    $env:R2_TEST_DB_NAME = $manualRecoveryBase
    $env:RUN_R2_MANUAL_LIVE_TEST = 'true'
    $env:RUN_R2_LIVE_TEST = 'true'
    $env:RUN_R2_MYSQL_TEST = 'true'
    $manualRecoveryStarted = Get-Date
    & .\gradlew.bat bootJar test --no-daemon --console=plain --rerun-tasks `
        --tests 'com.yeogidot.yeogidot.service.r2.R2DeletionManualRecoveryLiveIntegrationTest'
    $manualRecoveryExit = $LASTEXITCODE
    Write-Host "Test DB retained: $manualRecoverySchema"
    Write-Host 'Only generated fixture keys are cleaned up after assertions. Inspect only printed keys if cleanup fails.'
    if ($manualRecoveryExit -ne 0) { throw 'Manual recovery live test failed. Do not report success.' }
    $manualRecoveryXml = Join-Path $manualRecoveryRepo 'build/test-results/test/TEST-com.yeogidot.yeogidot.service.r2.R2DeletionManualRecoveryLiveIntegrationTest.xml'
    if (!(Test-Path -LiteralPath $manualRecoveryXml) -or
        (Get-Item -LiteralPath $manualRecoveryXml).LastWriteTime -lt $manualRecoveryStarted) {
        throw 'Fresh live test report not found'
    }
    [xml]$manualRecoveryReport = Get-Content -LiteralPath $manualRecoveryXml -Raw
    $manualRecoverySuite = $manualRecoveryReport.testsuite
    if ([int]$manualRecoverySuite.tests -ne 1 -or [int]$manualRecoverySuite.skipped -ne 0 -or
        [int]$manualRecoverySuite.failures -ne 0 -or [int]$manualRecoverySuite.errors -ne 0) {
        throw 'Expected exactly one passed, non-skipped live scenario'
    }
    $manualRecoveryEvidence = Join-Path $manualRecoveryRepo "docs/evidence/portfolio/r2-recovery/manual-live-$manualRecoveryBase"
    New-Item -ItemType Directory -Path $manualRecoveryEvidence | Out-Null
    Copy-Item -LiteralPath $manualRecoveryXml -Destination $manualRecoveryEvidence
    Write-Host 'MySQL + real test R2 + packaged CLI: 1 scenario passed, 0 failed, 0 skipped.'
    Write-Host 'Failure was injected before HTTP; test Clock/worker calls were controlled, not an actual R2 outage or scheduler timing test.'
    Write-Host "Report: $manualRecoveryXml"
    Write-Host "Evidence: $manualRecoveryEvidence"
} finally {
    foreach ($name in $manualRecoveryNames) {
        [Environment]::SetEnvironmentVariable($name, $manualRecoveryPrevious[$name], 'Process')
    }
    Pop-Location
}
