param(
    [string]$ModelFile = 'D:\workspace\xm-new\s-iot-modules-uav-detection\docs\TOFU-MS.json',
    [string]$GatewayJar = 'D:\workspace\xm-new\c-iot-gateway\c-iot-gateway-bootstrap\target\app.jar',
    [string]$RedisExe,
    [int]$RedisPort = 16379,
    [int]$PlatformPort = 18090,
    [int]$DevicePort = 8089
)
$ErrorActionPreference = 'Stop'
$labRepo = Split-Path $PSScriptRoot -Parent
$runtime = Join-Path $labRepo 'target/tofu-runtime'
$stateFile = Join-Path $runtime 'processes.json'
if (!$RedisExe) { $RedisExe = Join-Path $runtime 'redis/redis-server.exe' }
$webJar = Join-Path $labRepo 'device-platform-tofu-ms-gateway-web/target/device-platform-tofu-ms-gateway-web-1.0.0-SNAPSHOT.jar'
$simJar = Join-Path $labRepo 'device-platform-tofu-ms-simulator/target/device-platform-tofu-ms-simulator-1.0.0-SNAPSHOT.jar'
foreach ($file in @($ModelFile, $GatewayJar, $RedisExe, $webJar, $simJar)) {
    if (!(Test-Path -LiteralPath $file)) { throw "Missing $file. Build first; use -RedisExe to select a local Redis executable." }
}
$ModelFile = (Resolve-Path -LiteralPath $ModelFile).Path
$GatewayJar = (Resolve-Path -LiteralPath $GatewayJar).Path
$RedisExe = (Resolve-Path -LiteralPath $RedisExe).Path
if (Test-Path -LiteralPath $stateFile) { throw 'A saved chain exists. Run stop-tofu-ms-chain.ps1 first.' }
foreach ($port in @($RedisPort, $PlatformPort, $DevicePort)) {
    if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) { throw "Port $port is occupied." }
}
New-Item -ItemType Directory -Force -Path $runtime | Out-Null
$javaExe = (Get-Command java).Source
$libraries = Join-Path $labRepo 'device-platform-tofu-ms-gateway-web/target/lib/*'
$owned = [System.Collections.Generic.List[object]]::new()
function Start-ChainProcess([string]$name, [string]$exe, [string[]]$arguments) {
    $quoted = ($arguments | ForEach-Object { '"' + $_ + '"' }) -join ' '
    $process = Start-Process -FilePath $exe -ArgumentList $quoted -WorkingDirectory $runtime -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $runtime "$name.log") -RedirectStandardError (Join-Path $runtime "$name.err.log")
    $owned.Add([pscustomobject]@{ name = $name; id = $process.Id; startTicks = $process.StartTime.ToUniversalTime().Ticks })
    return $process
}
function Wait-Chain([scriptblock]$condition, [string]$description) {
    $until = [DateTime]::UtcNow.AddSeconds(25)
    do {
        foreach ($item in $owned) {
            if (!(Get-Process -Id $item.id -ErrorAction SilentlyContinue)) { throw "$($item.name) exited; inspect $runtime." }
        }
        try { if (& $condition) { return } } catch { }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $until)
    throw "Timed out waiting for $description; inspect $runtime."
}
try {
    Start-ChainProcess 'redis' $RedisExe @('--bind', '127.0.0.1', '--port', "$RedisPort", '--save', '', '--appendonly', 'no') | Out-Null
    Wait-Chain { Get-NetTCPConnection -State Listen -LocalPort $RedisPort -ErrorAction SilentlyContinue } 'Redis'
    Start-ChainProcess 'simulator' $javaExe @('-cp', "$simJar;$libraries", 'com.tu.deviceplatform.tofums.scenario.TofuMsSimulatorMain', "$DevicePort") | Out-Null
    Start-ChainProcess 'platform' $javaExe @('-cp', "$webJar;$libraries", 'com.tu.deviceplatform.tofumsweb.scenario.TofuMsGatewayWebMain', $ModelFile, "$PlatformPort", "--redis-port=$RedisPort") | Out-Null
    Wait-Chain { Invoke-RestMethod "http://127.0.0.1:$PlatformPort/api/state" -TimeoutSec 2 } 'P10 platform'
    Start-ChainProcess 'gateway' $javaExe @('-Dloader.main=com.xmkj.common.iot.gateway.tofums.TofuMsStandaloneApplication', '-cp', $GatewayJar,
        'org.springframework.boot.loader.PropertiesLauncher', "--spring.redis.port=$RedisPort", "--star.iot.gateway.rpc.url=http://127.0.0.1:$PlatformPort") | Out-Null
    Wait-Chain { (Invoke-RestMethod "http://127.0.0.1:$PlatformPort/api/state" -TimeoutSec 2).gateway.ready } 'P3 registration and heartbeat'
    [pscustomobject]@{ platformPort = $PlatformPort; devicePort = $DevicePort; processes = $owned.ToArray() } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $stateFile -Encoding UTF8
    Write-Host "Ready: http://127.0.0.1:$PlatformPort/ ; choose independent P3 mode, target 127.0.0.1:$DevicePort."
} catch {
    foreach ($item in $owned) {
        $process = Get-Process -Id $item.id -ErrorAction SilentlyContinue
        if ($process -and $process.StartTime.ToUniversalTime().Ticks -eq $item.startTicks) { Stop-Process -Id $item.id -Force }
    }
    throw
}
