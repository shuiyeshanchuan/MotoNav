param(
    [Parameter(Mandatory=$true)][string]$KotlinStdlib,
    [string]$JavaPath = $env:JAVA_HOME
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$java = if ($JavaPath) { Join-Path $JavaPath 'bin/java.exe' } else { 'java' }
$javac = if ($JavaPath) { Join-Path $JavaPath 'bin/javac.exe' } else { 'javac' }
$kotlinClasses = Join-Path $root 'app/build/tmp/kotlin-classes/release'
if (-not (Test-Path -LiteralPath $kotlinClasses)) { throw 'Build assembleRelease first.' }
if (-not (Test-Path -LiteralPath $KotlinStdlib)) { throw "Missing Kotlin standard library: $KotlinStdlib" }
$classes = Join-Path $root '.local/firmware-version-tests'
New-Item -ItemType Directory -Force $classes | Out-Null
$classpath = @($classes, $kotlinClasses, $KotlinStdlib) -join [IO.Path]::PathSeparator
$test = Join-Path $PSScriptRoot 'ota/com/example/motonav/FirmwareVersionOrderingTest.java'
& $javac -encoding UTF-8 -cp $classpath -d $classes $test
if ($LASTEXITCODE -ne 0) { throw 'Firmware version ordering test compilation failed' }
& $java -cp $classpath com.example.motonav.FirmwareVersionOrderingTest
if ($LASTEXITCODE -ne 0) { throw 'Firmware version ordering tests failed' }
