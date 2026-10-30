$ErrorActionPreference='Stop'
$taskJdk=$env:JAVA_HOME
if(!$taskJdk){throw 'Please set JAVA_HOME to JDK 17 or newer.'}
$classes=Join-Path $PSScriptRoot '.local/ble-tests'
New-Item -ItemType Directory -Force $classes | Out-Null
& (Join-Path $taskJdk 'bin/javac.exe') -encoding UTF-8 -d $classes (Join-Path $PSScriptRoot 'app/src/main/java/com/example/motonav/BleSession.java') (Join-Path $PSScriptRoot 'tests/ble/com/example/motonav/BleSessionTest.java')
if($LASTEXITCODE -ne 0){throw 'BLE tests compile failed'}
& (Join-Path $taskJdk 'bin/java.exe') -cp $classes com.example.motonav.BleSessionTest
if($LASTEXITCODE -ne 0){throw 'BLE tests failed'}

$otaClasses=Join-Path $PSScriptRoot '.local/ota-tests'
New-Item -ItemType Directory -Force $otaClasses | Out-Null
& (Join-Path $taskJdk 'bin/javac.exe') -encoding UTF-8 -d $otaClasses `
    (Join-Path $PSScriptRoot 'tests/ota/com/example/motonav/OtaBeginSizingTest.java')
if($LASTEXITCODE -ne 0){throw 'OTA sizing test compile failed'}
& (Join-Path $taskJdk 'bin/java.exe') -cp $otaClasses com.example.motonav.OtaBeginSizingTest
if($LASTEXITCODE -ne 0){throw 'OTA sizing tests failed'}
