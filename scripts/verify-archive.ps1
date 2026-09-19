# Offline Archive Integrity Audit
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Resolve-Path "$ScriptDir\.."
$ServiceDir = "$ProjectRoot\windows-service"

Write-Host "Running full cryptographic integrity verification on Windows Archive..." -ForegroundColor Cyan
& node "$ServiceDir\src\main.js" verify
