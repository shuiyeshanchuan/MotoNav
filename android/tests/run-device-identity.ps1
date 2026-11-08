param([string]$JavaHome = '')
$ErrorActionPreference = 'Stop'
$identityRepo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not $JavaHome) { $JavaHome = $env:JAVA_HOME }
if (-not $JavaHome) { $JavaHome = Get-ChildItem "$identityRepo/.tools/jdk/*" -Directory | Select-Object -First 1 -ExpandProperty FullName }
$identityOut = Join-Path $identityRepo 'android/.local/identity-tests'
New-Item -ItemType Directory -Force $identityOut | Out-Null
$identityCompiler = Join-Path $identityRepo '.tools/host-cpp/extract/w64devkit/bin/g++.exe'
& $identityCompiler -std=c++17 -O2 (Join-Path $identityRepo 'firmware/tests/IdentityTransferTest.cpp') -o "$identityOut/identity.exe"
if ($LASTEXITCODE -ne 0) { throw 'Identity firmware test compile failed' }
& "$identityOut/identity.exe" "$identityOut/native-frames.bin"
if ($LASTEXITCODE -ne 0) { throw 'Identity firmware tests failed' }
& "$JavaHome/bin/javac.exe" -encoding UTF-8 -d $identityOut (Join-Path $identityRepo 'android/app/src/main/java/com/example/motonav/DeviceIdentityProtocol.java') (Join-Path $PSScriptRoot 'DeviceIdentityProtocolTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Identity Android test compile failed' }
& "$JavaHome/bin/java.exe" -cp $identityOut com.example.motonav.DeviceIdentityProtocolTest "$identityOut/native-frames.bin"
if ($LASTEXITCODE -ne 0) { throw 'Identity cross-language tests failed' }
