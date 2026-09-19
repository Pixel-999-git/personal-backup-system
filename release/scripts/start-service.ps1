# Windows Background Service Starter
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Resolve-Path "$ScriptDir\.."
$ServiceDir = "$ProjectRoot\windows-service"

Write-Host "Starting Windows Backup Service in background..." -ForegroundColor Cyan
$Process = Start-Process -FilePath "node.exe" -ArgumentList "src/main.js run" -WorkingDirectory $ServiceDir -WindowStyle Hidden -PassThru

Set-Content -Path "$ServiceDir\service.pid" -Value $Process.Id
Write-Host "Windows Backup Service started with PID: $($Process.Id)" -ForegroundColor Green
Write-Host "Listening on configured port (default: 8976)" -ForegroundColor Green
