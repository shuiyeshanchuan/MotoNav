param(
    [ValidateSet('Check','Android','Firmware','All')]
    [string]$Task = 'Check',
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$tools = Join-Path $root '.tools'
$temp = Join-Path $tools 'tmp'
$sdk = Join-Path $tools 'android-sdk'
$gradleHome = Join-Path $tools 'gradle-cache'
$arduinoConfig = Join-Path $tools 'arduino-cli.yaml'
$gradle = 'D:\Configrition\gradle\gradle-8.10.2\bin\gradle.bat'
$arduinoCli = 'D:\Configrition\arduino-cli_1.5.2-rc.1_Windows_64bit\arduino-cli.exe'
$otaPrivateKey = 'D:\Configrition\MotoNav-ota-private.pem'

New-Item -ItemType Directory -Force $temp,$gradleHome | Out-Null
$java = Get-ChildItem -Path (Join-Path $tools 'jdk\*\bin\java.exe') -File -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $java) { throw "Project JDK not found under $tools\jdk" }
$jdk = Split-Path -Parent (Split-Path -Parent $java.FullName)

$required = @(
    (Join-Path $sdk 'platforms\android-35\android.jar'),
    (Join-Path $sdk 'build-tools\35.0.0\apksigner.bat'),
    $gradle,
    $arduinoCli,
    $arduinoConfig,
    $otaPrivateKey
)
$missing = @($required | Where-Object { -not (Test-Path -LiteralPath $_) })
if ($missing.Count) { throw "Required local paths are missing:`n$($missing -join "`n")" }

$env:JAVA_HOME = $jdk
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$env:GRADLE_USER_HOME = $gradleHome
$env:TEMP = $temp
$env:TMP = $temp
$env:Path = (Join-Path $jdk 'bin') + ';' + $env:Path

Write-Host 'MotoNav local environment is ready.' -ForegroundColor Green
Write-Host "JAVA_HOME:        $env:JAVA_HOME"
Write-Host "ANDROID_HOME:     $env:ANDROID_HOME"
Write-Host "GRADLE_USER_HOME: $env:GRADLE_USER_HOME"
Write-Host "TEMP/TMP:         $env:TEMP"
Write-Host "Gradle:          $gradle"
Write-Host "Arduino CLI:      $arduinoCli"

if ($Task -in @('Android','All')) {
    $androidArgs = @{
        JavaPath = $jdk
        SdkPath = $sdk
        GradlePath = $gradle
        Offline = $Offline
    }
    & (Join-Path $root 'Build-Personal.ps1') @androidArgs
    if ($LASTEXITCODE -ne 0) { throw "Android build failed ($LASTEXITCODE)" }
}

if ($Task -in @('Firmware','All')) {
    $pwsh = Get-Command pwsh -ErrorAction SilentlyContinue
    if (-not $pwsh) { throw 'PowerShell 7 (pwsh) is required to sign firmware OTA manifests.' }
    & $pwsh.Source -NoProfile -File (Join-Path $root 'firmware\build.ps1') -Board 175c -CliPath $arduinoCli -ConfigFile $arduinoConfig -OtaPrivateKey $otaPrivateKey
    if ($LASTEXITCODE -ne 0) { throw "Firmware build failed ($LASTEXITCODE)" }
}
