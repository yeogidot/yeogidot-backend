param()
$ErrorActionPreference = 'Stop'
$uploadRepository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$uploadPreviousProfile = [Environment]::GetEnvironmentVariable('RUN_UPLOAD_ALLOCATION_PROFILE', 'Process')
$uploadPreviousBaseline = [Environment]::GetEnvironmentVariable('RUN_UPLOAD_RESOURCE_BASELINE', 'Process')
Write-Host 'LOCAL DIAGNOSTIC ONLY: actual ImageIO/Thumbnailator; fake storage and DB. No production settings.'
Write-Host 'JFR enabled; one child JVM at a time; heap 512 MiB. Timings are NOT baseline comparisons.'
Write-Host 'Three conditions: standard-1-1, large-1-1, large-1-5. 1 warmup + 3 measured rounds each.'
Write-Host 'OOM is retained as a diagnostic outcome. Unexpected failures or missing recording events fail the tests.'
Push-Location -LiteralPath $uploadRepository
try {
    $env:RUN_UPLOAD_ALLOCATION_PROFILE = 'true'
    Remove-Item Env:RUN_UPLOAD_RESOURCE_BASELINE -ErrorAction SilentlyContinue
    & .\gradlew.bat test --tests '*PhotoUploadAllocationProfilerTest' --tests '*PhotoUploadResourceBenchmarkTest' --no-daemon --rerun-tasks
    if ($LASTEXITCODE -ne 0) { throw 'Allocation diagnostic failed. Inspect build/reports/upload-resource-baseline/<unique-run-id>/.' }
    Write-Host 'Evidence: build/reports/upload-resource-baseline/<unique-run-id>/allocation-* (.jfr, JSON, GC and process logs).'
} finally {
    Pop-Location
    if ($null -eq $uploadPreviousProfile) { Remove-Item Env:RUN_UPLOAD_ALLOCATION_PROFILE -ErrorAction SilentlyContinue }
    else { $env:RUN_UPLOAD_ALLOCATION_PROFILE = $uploadPreviousProfile }
    if ($null -eq $uploadPreviousBaseline) { Remove-Item Env:RUN_UPLOAD_RESOURCE_BASELINE -ErrorAction SilentlyContinue }
    else { $env:RUN_UPLOAD_RESOURCE_BASELINE = $uploadPreviousBaseline }
}
