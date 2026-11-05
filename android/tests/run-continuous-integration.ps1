param([string]$KotlinStdlib = '')
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskRepo = Split-Path -Parent $taskRoot
$taskJava = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
$taskJavac = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/javac.exe' } else { 'javac' }
if (-not $KotlinStdlib) {
    $KotlinStdlib = Get-ChildItem -LiteralPath (Join-Path $taskRepo '.tools/gradle-cache/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.0.21') -Recurse -Filter 'kotlin-stdlib-2.0.21.jar' | Select-Object -First 1 -ExpandProperty FullName
}
$taskKotlin = Join-Path $taskRoot 'app/build/tmp/kotlin-classes/release'
$taskProductionJava = Join-Path $taskRoot 'app/build/intermediates/javac/release/compileReleaseJavaWithJavac/classes'
if (-not (Test-Path -LiteralPath (Join-Path $taskKotlin 'com/example/motonav/ContinuousImuRecorder.class'))) { throw 'Build Android Release first; this harness tests its actual Kotlin class.' }
$taskClasses = Join-Path $taskRoot '.local/continuous-integration-classes'
New-Item -ItemType Directory -Force $taskClasses | Out-Null
$taskCompileClasspath = "$taskKotlin;$taskProductionJava;$KotlinStdlib"
$taskSources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'continuous-integration') -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName)
& $taskJavac -encoding UTF-8 -cp $taskCompileClasspath -d $taskClasses @taskSources
if ($LASTEXITCODE -ne 0) { throw 'Continuous recorder integration harness compilation failed' }
$taskRuntimeClasspath = "$taskClasses;$taskKotlin;$taskProductionJava;$KotlinStdlib"
$taskFixture = Join-Path $taskRoot ('.local/continuous-integration-' + [Guid]::NewGuid().ToString('N'))
foreach ($taskMode in @('normal', 'reopen', 'interruptions', 'fault-reopen')) {
    & $taskJava '-Xmx64m' -cp $taskRuntimeClasspath com.example.motonav.ContinuousImuRecorderIntegrationTest $taskFixture $taskMode
    if ($LASTEXITCODE -ne 0) { throw "Actual Kotlin recorder integration failed: $taskMode" }
}
