$ErrorActionPreference = 'Stop'
$runtime = Join-Path (Split-Path $PSScriptRoot -Parent) 'target/tofu-runtime'
$stateFile = Join-Path $runtime 'processes.json'
if (!(Test-Path -LiteralPath $stateFile)) { Write-Host 'No saved chain.'; return }
$chain = Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json
try { Invoke-RestMethod "http://127.0.0.1:$($chain.platformPort)/api/disconnect" -Method Post -ContentType application/json -Body '{}' -TimeoutSec 25 | Out-Null }
catch { Write-Warning "Disconnect was not confirmed: $($_.Exception.Message)" }
foreach ($name in @('gateway', 'platform', 'simulator', 'redis')) {
    foreach ($item in $chain.processes | Where-Object name -eq $name) {
        $process = Get-Process -Id $item.id -ErrorAction SilentlyContinue
        if ($process -and $process.StartTime.ToUniversalTime().Ticks -eq $item.startTicks) { Stop-Process -Id $item.id -Force }
    }
}
Remove-Item -LiteralPath $stateFile
Write-Host 'Local simulation chain stopped.'
