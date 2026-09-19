# Windows Background Service Stopper
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Resolve-Path "$ScriptDir\.."
$ServiceDir = "$ProjectRoot\windows-service"
$PidFile = "$ServiceDir\service.pid"

if (Test-Path $PidFile) {
    $ProcessId = Get-Content $PidFile
    Write-Host "Stopping Windows Backup Service (PID: $ProcessId)..." -ForegroundColor Yellow
    Stop-Process -Id $ProcessId -Force -ErrorAction SilentlyContinue
    Remove-Item $PidFile -Force -ErrorAction SilentlyContinue
    Write-Host "Windows Backup Service stopped." -ForegroundColor Green
} else {
    Write-Host "No PID file found. Checking for running instances..." -ForegroundColor Yellow
    Get-Process -Name "node" -ErrorAction SilentlyContinue | Where-Object { $_.CommandLine -match "windows-backup-service" } | Stop-Process -Force
    Write-Host "Done." -ForegroundColor Green
}
