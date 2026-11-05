param([string]$JavaHome = '', [string]$JsonJar = '', [string]$KotlinStdlib = '', [string[]]$Modes = @('normal','seed-countdown','restart-countdown','seed-claimed','restart-claimed','seed-submitted','restart-submitted','blocked-init','corrupt-init'))
$ErrorActionPreference = 'Stop'
$taskAndroid = Split-Path -Parent $PSScriptRoot
$taskRepo = Split-Path -Parent $taskAndroid
if (-not $JavaHome) { $JavaHome = $env:JAVA_HOME }
if (-not $JavaHome) { $JavaHome = Join-Path $taskRepo '.tools/jdk/jdk-21.0.12.1+1' }
$taskJava = Join-Path $JavaHome 'bin/java.exe'
$taskJavac = Join-Path $JavaHome 'bin/javac.exe'
if (-not $JsonJar) { $JsonJar = Join-Path $taskRepo '.tools/test-deps/json-20240303.jar' }
if (-not $KotlinStdlib) {
    $KotlinStdlib = Get-ChildItem -LiteralPath (Join-Path $taskRepo '.tools/gradle-cache/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.0.21') -Recurse -Filter 'kotlin-stdlib-2.0.21.jar' | Select-Object -First 1 -ExpandProperty FullName
}
if (-not (Test-Path -LiteralPath $JsonJar)) { throw 'Official org.json test runtime jar is missing' }
$taskKotlin = Join-Path $taskAndroid 'app/build/tmp/kotlin-classes/release'
if (-not (Test-Path -LiteralPath (Join-Path $taskKotlin 'com/example/motonav/EmergencyAlertController.class'))) { throw 'Build Android Release first; this harness tests its actual Kotlin controller and SMS sender classes' }
$taskClasses = Join-Path $taskAndroid ('.local/emergency-integration-classes-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $taskClasses | Out-Null
$taskSources = @(
    (Join-Path $taskAndroid 'app/src/main/java/com/example/motonav/ContinuousImuProtocol.java'),
    (Join-Path $taskAndroid 'app/src/main/java/com/example/motonav/BrakeTestDetector.java'),
    (Join-Path $taskAndroid 'app/src/main/java/com/example/motonav/EmergencyAlertState.java')
) + @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'emergency-integration') -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName)
& $taskJavac -encoding UTF-8 -cp "$taskKotlin;$KotlinStdlib;$JsonJar" -d $taskClasses @taskSources
if ($LASTEXITCODE -ne 0) { throw 'Emergency integration substitute compilation failed' }
$taskRuntimeClasspath = "$taskClasses;$taskKotlin;$KotlinStdlib;$JsonJar"
$taskFixture = Join-Path $taskAndroid ('.local/emergency-integration-fixture-' + [Guid]::NewGuid().ToString('N'))
foreach ($taskMode in $Modes) {
    & $taskJava '-Xmx64m' -cp $taskRuntimeClasspath com.example.motonav.EmergencyIntegrationTest $taskFixture $taskMode
    if ($LASTEXITCODE -ne 0) { throw "Actual emergency Kotlin controller integration failed: $taskMode" }
}
Write-Output "Emergency integration passed; actual compiled Kotlin classes; all modem/network APIs replaced. Fixtures: $taskFixture"
