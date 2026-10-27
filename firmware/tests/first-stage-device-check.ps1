param(
  [string]$Port = 'COM8',
  [int]$BaudRate = 115200,
  [int]$TimeoutSeconds = 8
)

$ErrorActionPreference = 'Stop'
$serial = [System.IO.Ports.SerialPort]::new($Port, $BaudRate, [System.IO.Ports.Parity]::None, 8, [System.IO.Ports.StopBits]::One)
$serial.DtrEnable = $false
$serial.RtsEnable = $false
$serial.ReadTimeout = 250
$serial.Open()
try {
  $lines = [System.Collections.Generic.List[string]]::new()
  $endAt = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
  while ([DateTime]::UtcNow -lt $endAt) {
    try {
      $line = $serial.ReadLine().Trim()
      if ($line) { $lines.Add($line); Write-Host $line }
    } catch [TimeoutException] { }
  }
} finally {
  $serial.Close()
  $serial.Dispose()
}

$info = $lines | Where-Object { $_ -like 'INFO,*' } | Select-Object -First 1
$self = $lines | Where-Object { $_ -like 'SELFTEST,*' } | Select-Object -First 1
if (-not $info) { throw '没有收到 INFO。请确认设备已启动、端口正确且串口监视器没有占用该端口。' }
if (-not $self) { throw '没有收到 SELFTEST。' }

$ip = $info -split ','
$sp = $self -split ','
if ($ip.Count -lt 9 -or $ip[1] -ne '175C') { throw "板型不匹配：$info" }
if ($ip[5] -ne '1') { throw "固件没有报告 OTA_READY=1：$info" }
if ($sp.Count -lt 18) { throw "SELFTEST 字段数量不足：$self" }
Write-Host "PASS: 1.75C startup diagnostics received; OTA_READY=1; SELFTEST fields=$($sp.Count)" -ForegroundColor Green
