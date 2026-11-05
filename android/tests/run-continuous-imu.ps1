param([string]$JavaHome = '', [switch]$SkipLongRun)
$ErrorActionPreference = 'Stop'
$taskAndroid = Split-Path -Parent $PSScriptRoot
$taskRepo = Split-Path -Parent $taskAndroid
if (-not $JavaHome) { $JavaHome = $env:JAVA_HOME }
if (-not $JavaHome) {
    $taskLocalJdk = Join-Path $taskRepo '.tools/jdk/jdk-21.0.12.1+1'
    if (Test-Path -LiteralPath $taskLocalJdk) { $JavaHome = $taskLocalJdk }
}
$taskJava = if ($JavaHome) { Join-Path $JavaHome 'bin/java.exe' } else { 'java' }
$taskJavac = if ($JavaHome) { Join-Path $JavaHome 'bin/javac.exe' } else { 'javac' }
$taskClasses = Join-Path $taskAndroid '.local/continuous-imu-classes'
New-Item -ItemType Directory -Force $taskClasses | Out-Null
$taskSources = @(
    (Join-Path $taskAndroid 'app/src/main/java/com/example/motonav/ContinuousImuProtocol.java'),
    (Join-Path $taskAndroid 'app/src/main/java/com/example/motonav/ContinuousImuStore.java'),
    (Join-Path $PSScriptRoot 'ContinuousImuProtocolTest.java'),
    (Join-Path $PSScriptRoot 'ContinuousImuStoreTest.java')
)
& $taskJavac -encoding UTF-8 -d $taskClasses @taskSources
if ($LASTEXITCODE -ne 0) { throw 'Continuous IMU standalone Java compilation failed' }
& $taskJava '-Xmx32m' -cp $taskClasses com.example.motonav.ContinuousImuProtocolTest
if ($LASTEXITCODE -ne 0) { throw 'Continuous IMU protocol test failed' }
$taskFixture = Join-Path $taskAndroid ('.local/continuous-imu-fixture-' + [Guid]::NewGuid().ToString('N'))
foreach ($taskMode in @('basic', 'seed', 'recover')) {
    & $taskJava '-Xmx32m' -cp $taskClasses com.example.motonav.ContinuousImuStoreTest $taskFixture $taskMode
    if ($LASTEXITCODE -ne 0) { throw "Continuous IMU store test failed: $taskMode" }
}
if (-not $SkipLongRun) {
    & $taskJava '-Xmx32m' -cp $taskClasses com.example.motonav.ContinuousImuStoreTest (Join-Path $taskFixture 'long') long
    if ($LASTEXITCODE -ne 0) { throw 'Continuous IMU synthetic one-hour storage test failed' }
}
Write-Output "Standalone continuous IMU tests passed; synthetic fixtures: $taskFixture"
