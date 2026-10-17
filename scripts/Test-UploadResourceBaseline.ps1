param([ValidateSet('smoke', 'explore')][string]$Mode = 'smoke')
$ErrorActionPreference = 'Stop'
$uploadRepository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$uploadPreviousOptIn = [Environment]::GetEnvironmentVariable('RUN_UPLOAD_RESOURCE_BASELINE', 'Process')
Write-Host 'LOCAL ONLY: actual image validation/compression, no DB/R2/Kakao connections.'
Write-Host 'One child JVM at a time; measurement heap 512 MiB; no production settings loaded.'
if ($Mode -eq 'explore') {
    Write-Host 'Exploration: generated 1600x1200/4000x3000 JPEGs; 8 conditions; 1 warmup + 3 measured rounds.'
    Write-Host 'Synthetic storage delay is 50 ms. This is not actual R2 throughput or production API latency.'
    $env:RUN_UPLOAD_RESOURCE_BASELINE = 'true'
} else {
    Write-Host 'Smoke only: tiny image contract tests. Heavy conditions are skipped.'
    Remove-Item Env:RUN_UPLOAD_RESOURCE_BASELINE -ErrorAction SilentlyContinue
}
Push-Location -LiteralPath $uploadRepository
try {
    & .\gradlew.bat test --tests '*PhotoUploadResourceBenchmarkTest' --no-daemon --rerun-tasks
    if ($LASTEXITCODE -ne 0) { throw 'Baseline test failed. Inspect build/reports/upload-resource-baseline for results and logs.' }
    Write-Host 'Results: build/reports/upload-resource-baseline/<unique-run-id>/'
} finally {
    Pop-Location
    if ($null -eq $uploadPreviousOptIn) { Remove-Item Env:RUN_UPLOAD_RESOURCE_BASELINE -ErrorAction SilentlyContinue }
    else { $env:RUN_UPLOAD_RESOURCE_BASELINE = $uploadPreviousOptIn }
}
