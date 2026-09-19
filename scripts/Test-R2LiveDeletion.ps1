param()
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$names = @('RUN_R2_LIVE_TEST','RUN_R2_MYSQL_TEST','R2_LIVE_BUCKET','R2_LIVE_ENDPOINT',
    'R2_LIVE_ACCESS_KEY_ID','R2_LIVE_SECRET_ACCESS_KEY','R2_TEST_DB_NAME','R2_TEST_DB_PORT',
    'R2_TEST_DB_USERNAME','R2_TEST_DB_PASSWORD','MYSQL_PWD')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }

function Read-PrivateValue([string]$Label) {
    $secret = Read-Host $Label -AsSecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer); $secret.Dispose() }
}

Push-Location $repo
try {
    Write-Host 'LIVE TEST ONLY: bucket=yeogidot-r2-delete-test, DB host=127.0.0.1'
    Write-Host 'Use credentials scoped ONLY to this test bucket. Never use the production token.'
    Write-Host 'Creates one new local MySQL schema and up to three tiny temporary R2 objects.'
    $endpoint = (Read-Host 'S3 account endpoint (https://ACCOUNT_ID.r2.cloudflarestorage.com)').Trim()
    if ($endpoint -cnotmatch '^https://[a-f0-9]{32}\.r2\.cloudflarestorage\.com/?$') {
        throw 'Invalid endpoint. Do not include the bucket name or a path.'
    }
    $env:R2_LIVE_ENDPOINT = $endpoint
    $env:R2_LIVE_BUCKET = 'yeogidot-r2-delete-test'
    $env:R2_LIVE_ACCESS_KEY_ID = Read-PrivateValue 'Test-only Access Key ID (hidden)'
    $env:R2_LIVE_SECRET_ACCESS_KEY = Read-PrivateValue 'Test-only Secret Access Key (hidden)'
    $port = Read-Host 'Local MySQL port [3306]'
    if ([string]::IsNullOrWhiteSpace($port)) { $port = '3306' }
    if ($port -notmatch '^[0-9]{1,5}$' -or [int]$port -lt 1 -or [int]$port -gt 65535) { throw 'Invalid port' }
    $env:R2_TEST_DB_PORT = $port
    $env:R2_TEST_DB_USERNAME = Read-Host 'Local MySQL user (must be able to create a test database)'
    $env:R2_TEST_DB_PASSWORD = Read-PrivateValue 'Local MySQL password (hidden)'
    foreach ($name in @('R2_LIVE_ACCESS_KEY_ID','R2_LIVE_SECRET_ACCESS_KEY','R2_TEST_DB_USERNAME','R2_TEST_DB_PASSWORD')) {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name, 'Process'))) { throw "Missing $name" }
    }
    $base = 'yeogidot_r2_test_' + (Get-Date -Format 'yyyyMMdd') + '_' + [Guid]::NewGuid().ToString('N').Substring(0,8)
    $schema = $base + '_photo'
    if ($schema -notmatch '^yeogidot_r2_test_[0-9]{8}_[a-f0-9]{8}_photo$') { throw 'Invalid generated schema' }
    Write-Host "Target MySQL: 127.0.0.1:$port / $schema"
    if ((Read-Host 'Type RUN to create the test DB and upload/delete test objects') -cne 'RUN') { throw 'Cancelled' }
    $mysqlCommand = Get-Command mysql.exe -ErrorAction SilentlyContinue
    $mysql = if ($mysqlCommand) { $mysqlCommand.Source } else { 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe' }
    if (!(Test-Path -LiteralPath $mysql)) { throw 'mysql.exe not found' }
    $env:MYSQL_PWD = $env:R2_TEST_DB_PASSWORD
    & $mysql --host=127.0.0.1 "--port=$port" "--user=$env:R2_TEST_DB_USERNAME" "--execute=CREATE DATABASE $schema CHARACTER SET utf8mb4;"
    if ($LASTEXITCODE -ne 0) { throw 'Fresh test database creation failed' }
    [Environment]::SetEnvironmentVariable('MYSQL_PWD', $previous['MYSQL_PWD'], 'Process')
    $env:R2_TEST_DB_NAME = $base
    $env:RUN_R2_LIVE_TEST = 'true'
    $env:RUN_R2_MYSQL_TEST = 'true'
    $started = Get-Date
    & .\gradlew.bat test --no-daemon --console=plain --rerun-tasks --tests 'com.yeogidot.yeogidot.service.R2DeletionLiveIntegrationTest'
    $testExit = $LASTEXITCODE
    $xml = Join-Path $repo 'build/test-results/test/TEST-com.yeogidot.yeogidot.service.R2DeletionLiveIntegrationTest.xml'
    if ((Test-Path -LiteralPath $xml) -and (Get-Item -LiteralPath $xml).LastWriteTime -ge $started) {
        $evidence = Join-Path $repo "docs/evidence/portfolio/r2-recovery/live-$base"
        New-Item -ItemType Directory -Path $evidence | Out-Null
        Copy-Item -LiteralPath $xml -Destination $evidence
        Write-Host "Evidence: $evidence"
    }
    Write-Host "Test DB retained for inspection: $schema"
    Write-Host 'Objects are cleaned up after assertions. If cleanup failed, inspect only the printed fixture keys.'
    if ($testExit -ne 0) { throw 'Live tests failed. Do not report them as passed.' }
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
    Pop-Location
}
