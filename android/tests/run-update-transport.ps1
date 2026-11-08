param([string]$JavaPath = $env:JAVA_HOME, [Parameter(Mandatory)][string]$KotlinStdlib)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$repo = Split-Path -Parent $root
$java = Join-Path $JavaPath 'bin/java.exe'
$javac = Join-Path $JavaPath 'bin/javac.exe'
$classes = Join-Path $root '.local/update-transport-tests'
New-Item -ItemType Directory -Force $classes | Out-Null
$classpath = @($classes, "$root/app/build/tmp/kotlin-classes/release", $KotlinStdlib,
    "$repo/.tools/test-deps/json-20240303.jar", "$repo/.tools/android-sdk/platforms/android-35/android.jar") -join [IO.Path]::PathSeparator
& $javac -encoding UTF-8 -cp $classpath -d $classes "$PSScriptRoot/ota/com/example/motonav/UpdateTransportTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Update transport test compilation failed' }
& $java -cp $classpath com.example.motonav.UpdateTransportTest
if ($LASTEXITCODE -ne 0) { throw 'Update transport tests failed' }
