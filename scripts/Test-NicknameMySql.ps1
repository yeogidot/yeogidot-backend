param()
$ErrorActionPreference = 'Stop'
$nicknameRepo = Split-Path -Parent $PSScriptRoot
$nicknameEnvNames = @('RUN_NICKNAME_MYSQL_TEST', 'NICKNAME_TEST_DB_NAME', 'NICKNAME_TEST_DB_PORT',
    'NICKNAME_TEST_DB_USERNAME', 'NICKNAME_TEST_DB_PASSWORD', 'MYSQL_PWD')
$nicknamePreviousEnv = @{}
foreach ($name in $nicknameEnvNames) {
    $nicknamePreviousEnv[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

function Read-NicknamePrivateValue([string]$Label) {
    $secret = Read-Host $Label -AsSecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer); $secret.Dispose() }
}

Push-Location $nicknameRepo
try {
    Write-Host 'LOCAL MYSQL ONLY: host=127.0.0.1. No production settings, Redis or R2 are loaded.'
    Write-Host 'Creates a NEW dedicated nickname test database; leaves it for inspection.'
    $nicknamePort = Read-Host 'Local MySQL port [3306]'
    if ([string]::IsNullOrWhiteSpace($nicknamePort)) { $nicknamePort = '3306' }
    if ($nicknamePort -notmatch '^[0-9]{1,5}$' -or [int]$nicknamePort -lt 1 -or [int]$nicknamePort -gt 65535) {
        throw 'Invalid local MySQL port'
    }
    $env:NICKNAME_TEST_DB_PORT = $nicknamePort
    $env:NICKNAME_TEST_DB_USERNAME = Read-Host 'Local MySQL user (must be able to create a test database)'
    $env:NICKNAME_TEST_DB_PASSWORD = Read-NicknamePrivateValue 'Local MySQL password (hidden)'
    if ([string]::IsNullOrWhiteSpace($env:NICKNAME_TEST_DB_USERNAME) -or
        [string]::IsNullOrWhiteSpace($env:NICKNAME_TEST_DB_PASSWORD)) { throw 'Missing local credentials' }
    $nicknameSchema = 'yeogidot_nickname_test_' + (Get-Date -Format 'yyyyMMdd') + '_' +
        [Guid]::NewGuid().ToString('N').Substring(0, 8)
    if ($nicknameSchema -cnotmatch '^yeogidot_nickname_test_[0-9]{8}_[a-f0-9]{8}$') { throw 'Invalid schema' }
    Write-Host "Target: 127.0.0.1:$nicknamePort / $nicknameSchema"
    if ((Read-Host 'Type RUN to create the local test database and run tests') -cne 'RUN') { throw 'Cancelled' }
    $nicknameMysqlCommand = Get-Command mysql.exe -ErrorAction SilentlyContinue
    $nicknameMysql = if ($nicknameMysqlCommand) { $nicknameMysqlCommand.Source }
        else { 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe' }
    if (!(Test-Path -LiteralPath $nicknameMysql)) { throw 'mysql.exe not found' }
    $env:MYSQL_PWD = $env:NICKNAME_TEST_DB_PASSWORD
    & $nicknameMysql --no-defaults --protocol=TCP --host=127.0.0.1 "--port=$nicknamePort" `
        "--user=$env:NICKNAME_TEST_DB_USERNAME" "--execute=CREATE DATABASE $nicknameSchema CHARACTER SET utf8mb4;"
    if ($LASTEXITCODE -ne 0) { throw 'Fresh test database creation failed' }
    [Environment]::SetEnvironmentVariable('MYSQL_PWD', $nicknamePreviousEnv['MYSQL_PWD'], 'Process')
    $env:NICKNAME_TEST_DB_NAME = $nicknameSchema
    $env:RUN_NICKNAME_MYSQL_TEST = 'true'
    $nicknameStarted = Get-Date
    & .\gradlew.bat test --no-daemon --console=plain --rerun-tasks --tests '*Nickname*'
    $nicknameTestExit = $LASTEXITCODE
    Write-Host "Test DB retained: $nicknameSchema"
    if ($nicknameTestExit -ne 0) { throw 'Tests failed. Do not report them as passed.' }
    $nicknameXml = Join-Path $nicknameRepo 'build/test-results/test/TEST-com.yeogidot.yeogidot.service.AuthNicknameMySqlIntegrationTest.xml'
    if (!(Test-Path -LiteralPath $nicknameXml) -or (Get-Item -LiteralPath $nicknameXml).LastWriteTime -lt $nicknameStarted) {
        throw 'Fresh MySQL test report not found'
    }
    [xml]$nicknameReport = Get-Content -LiteralPath $nicknameXml -Raw
    $nicknameSuite = $nicknameReport.testsuite
    if ([int]$nicknameSuite.tests -ne 14 -or [int]$nicknameSuite.skipped -ne 0 -or
        [int]$nicknameSuite.failures -ne 0 -or [int]$nicknameSuite.errors -ne 0) {
        throw 'MySQL tests were skipped, incomplete or failed'
    }
    Write-Host 'MySQL nickname tests: 14 passed, 0 failed, 0 skipped.'
    Write-Host "Report: $nicknameXml"
} finally {
    foreach ($name in $nicknameEnvNames) {
        [Environment]::SetEnvironmentVariable($name, $nicknamePreviousEnv[$name], 'Process')
    }
    Pop-Location
}
