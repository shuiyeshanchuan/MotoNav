param(
    [string]$CompilerPath = '',
    [string]$OutputDirectory = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
if (-not $CompilerPath) {
    $CompilerPath = Join-Path $projectRoot '.tools/host-cpp/extract/w64devkit/bin/g++.exe'
    if (-not (Test-Path -LiteralPath $CompilerPath)) {
        $CompilerPath = (Get-Command 'g++' -ErrorAction Stop).Source
    }
}
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $projectRoot '.tools/tmp/imu-acquisition-native'
}
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null

$cases = @(
    'ImuAcquisitionQueueTest',
    'ImuSamplingAdapterTest',
    'ContinuousImuRecordTest',
    'ImuMountReferenceTest',
    'EmergencyAlarmTest',
    'IdlePowerSupplyTest'
)
foreach ($case in $cases) {
    $executable = Join-Path $OutputDirectory ($case + '.exe')
    $compileArguments = @('-std=c++17', '-O2', '-Wall', '-Wextra', '-Werror', '-pthread')
    if ($case -eq 'ImuSamplingAdapterTest') {
        $compileArguments += '-I' + (Join-Path $PSScriptRoot 'imu-acquisition-stubs')
    }
    $compileArguments += @((Join-Path $PSScriptRoot ($case + '.cpp')), '-o', $executable)
    & $CompilerPath @compileArguments
    if ($LASTEXITCODE -ne 0) { throw "Compile failed: $case ($LASTEXITCODE)" }
    & $executable
    if ($LASTEXITCODE -ne 0) { throw "Test failed: $case ($LASTEXITCODE)" }
}
Write-Output "PASS: $($cases.Count) IMU acquisition and related native regressions"
