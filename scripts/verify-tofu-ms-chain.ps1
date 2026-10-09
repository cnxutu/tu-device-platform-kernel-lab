param(
    [string]$ModelFile = 'D:\workspace\xm-new\s-iot-modules-uav-detection\docs\TOFU-MS.json',
    [string]$GatewayJar = 'D:\workspace\xm-new\c-iot-gateway\c-iot-gateway-bootstrap\target\app.jar',
    [string]$RedisExe,
    [int]$RedisPort = 16380,
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
$labRepo = Split-Path $PSScriptRoot -Parent
$runtime = Join-Path $labRepo 'target/tofu-runtime'
if (!$RedisExe) { $RedisExe = Join-Path $runtime 'redis/redis-server.exe' }
foreach ($file in @($RedisExe, $ModelFile, $GatewayJar)) { if (!(Test-Path -LiteralPath $file)) { throw "Missing $file" } }
if (Get-NetTCPConnection -State Listen -LocalPort $RedisPort -ErrorAction SilentlyContinue) { throw "Test Redis port $RedisPort is occupied; choose another port." }
New-Item -ItemType Directory -Force -Path $runtime | Out-Null
$testRedis = Start-Process -FilePath $RedisExe -ArgumentList "--bind 127.0.0.1 --port $RedisPort --save `"`" --appendonly no" `
    -WorkingDirectory $runtime -WindowStyle Hidden -PassThru -RedirectStandardOutput "$runtime/verify-redis.log" -RedirectStandardError "$runtime/verify-redis.err.log"
$startTicks = $testRedis.StartTime.ToUniversalTime().Ticks
Push-Location $labRepo
try {
    $until = [DateTime]::UtcNow.AddSeconds(10)
    while (!(Get-NetTCPConnection -State Listen -LocalPort $RedisPort -ErrorAction SilentlyContinue)) {
        if ($testRedis.HasExited -or [DateTime]::UtcNow -ge $until) { throw 'Test Redis failed to start.' }
        Start-Sleep -Milliseconds 200
    }
    $arguments = @('-pl', 'device-platform-tofu-ms-gateway-web', '-am', 'test', "-Dtofu.tsl.path=$ModelFile", "-Dtofu.gateway.jar=$GatewayJar", "-Dtofu.redis.port=$RedisPort")
    if ($Offline) { $arguments = @('-o') + $arguments }
    & mvn @arguments
    if ($LASTEXITCODE -ne 0) { throw 'Chain verification failed; inspect surefire reports and external-gateway-chain.log.' }
} finally {
    Pop-Location
    $process = Get-Process -Id $testRedis.Id -ErrorAction SilentlyContinue
    if ($process -and $process.StartTime.ToUniversalTime().Ticks -eq $startTicks) { Stop-Process -Id $testRedis.Id -Force }
}
