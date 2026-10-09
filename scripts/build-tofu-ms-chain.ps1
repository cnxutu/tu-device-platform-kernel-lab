param(
    [string]$ModelRepo = 'D:\workspace\xm-new\s-iot-modules-uav-detection',
    [string]$AdapterRepo = 'D:\workspace\xm-new\ad-iot-codec-adapter-uav-detection',
    [string]$GatewayRepo = 'D:\workspace\xm-new\c-iot-gateway',
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
$labRepo = Split-Path $PSScriptRoot -Parent
function Invoke-ChainMaven([string]$repo, [string[]]$arguments) {
    Push-Location $repo
    try {
        if ($Offline) { $arguments = @('-o') + $arguments }
        & mvn @arguments
        if ($LASTEXITCODE -ne 0) { throw "Maven failed in $repo" }
    } finally { Pop-Location }
}
Invoke-ChainMaven $ModelRepo @('install', '-Dmaven.javadoc.skip=true', '-DskipTests=false')
Invoke-ChainMaven $AdapterRepo @('-pl', 'ad-iot-codec-adapter-uav-detection-tofu-ms', '-am', 'install', '-Dmaven.javadoc.skip=true', '-DskipTests=false')
Invoke-ChainMaven $labRepo @('-pl', 'device-platform-tofu-ms-simulator', '-am', 'install')
Invoke-ChainMaven $GatewayRepo @('-pl', 'c-iot-gateway-bootstrap', '-am', '-Ptofu-ms-local', 'install', '-DskipTests=false')
Invoke-ChainMaven $labRepo @('-pl', 'device-platform-tofu-ms-gateway-web', '-am', 'package', "-Dtofu.tsl.path=$ModelRepo/docs/TOFU-MS.json")
