param([string]$JavaHome = '')
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
$taskClasses = Join-Path $taskAndroid '.local/brake-test-classes'
New-Item -ItemType Directory -Force $taskClasses | Out-Null
$taskSources = @(
    (Join-Path $taskAndroid 'app/src/main/java/com/example/motonav/BrakeTestDetector.java'),
    (Join-Path $taskAndroid 'app/src/main/java/com/example/motonav/EmergencyAlertState.java'),
    (Join-Path $PSScriptRoot 'BrakeTestDetectorTest.java'),
    (Join-Path $PSScriptRoot 'EmergencyAlertStateTest.java')
)
& $taskJavac -encoding UTF-8 -d $taskClasses @taskSources
if ($LASTEXITCODE -ne 0) { throw 'Brake TEST Java compilation failed' }
foreach ($taskTest in @('BrakeTestDetectorTest','EmergencyAlertStateTest')) {
    & $taskJava '-Xmx32m' -cp $taskClasses "com.example.motonav.$taskTest"
    if ($LASTEXITCODE -ne 0) { throw "Brake TEST failed: $taskTest" }
}
