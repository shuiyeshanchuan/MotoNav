param([string]$JavaHome = '', [string]$KotlinStdlib = '', [string]$JsonJar = '')
$ErrorActionPreference = 'Stop'
$taskAndroid = Split-Path -Parent $PSScriptRoot
$taskRepo = Split-Path -Parent $taskAndroid
if (-not $JavaHome) { $JavaHome = $env:JAVA_HOME }
if (-not $JavaHome) { $JavaHome = 'D:/vibecoding/MotoNav/.tools/jdk/jdk-21.0.12.1+1' }
if (-not $KotlinStdlib) {
    $KotlinStdlib = Get-ChildItem -LiteralPath (Join-Path $taskRepo '.tools/gradle-cache/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.0.21') -Recurse -Filter 'kotlin-stdlib-2.0.21.jar' | Select-Object -First 1 -ExpandProperty FullName
}
if (-not $JsonJar) { $JsonJar = Join-Path $taskRepo '.tools/test-deps/json-20240303.jar' }
$taskKotlin = Join-Path $taskAndroid 'app/build/tmp/kotlin-classes/release'
if (-not (Test-Path -LiteralPath (Join-Path $taskKotlin 'com/example/motonav/FirmwareUpdateClient.class'))) { throw 'Build Android Release first.' }
$taskClasses = Join-Path $taskAndroid '.local/firmware-update-tests'
New-Item -ItemType Directory -Force $taskClasses | Out-Null
$taskClasspath = "$taskClasses;$taskKotlin;$KotlinStdlib;$JsonJar"
$taskSources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'firmware-update') -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName)
& (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 -cp $taskClasspath -d $taskClasses @taskSources
if ($LASTEXITCODE -ne 0) { throw 'Firmware update policy test compilation failed' }
& (Join-Path $JavaHome 'bin/java.exe') '-Xmx64m' -cp $taskClasspath com.example.motonav.FirmwareUpdateSourcePolicyTest
if ($LASTEXITCODE -ne 0) { throw 'Firmware feed/fallback policy failed' }
