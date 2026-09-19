# Register Windows Backup Service for Automatic Startup on Windows Boot
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Resolve-Path "$ScriptDir\.."
$ServiceDir = "$ProjectRoot\windows-service"

Write-Host "Configuring automatic Windows startup for Windows Backup Service..." -ForegroundColor Cyan

$TaskName = "WindowsBackupArchiveService"
$NodePath = (Get-Command node.exe).Source
$MainJs = "$ServiceDir\src\main.js"

$Action = New-ScheduledTaskAction -Execute $NodePath -Argument "`"$MainJs`" run" -WorkingDirectory $ServiceDir
$Trigger = New-ScheduledTaskTrigger -AtLogon
$Principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive -RunLevel Highest
$Settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit 0

try {
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
    Register-ScheduledTask -TaskName $TaskName -Action $Action -Trigger $Trigger -Principal $Principal -Settings $Settings -Description "Automatic Android to Windows Personal Backup Archive Daemon" | Out-Null
    Write-Host "Scheduled task '$TaskName' registered successfully to start automatically at logon." -ForegroundColor Green
} catch {
    Write-Warning "Could not register Scheduled Task (elevation might be needed). Falling back to Registry Startup..."
    $RegKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run"
    Set-ItemProperty -Path $RegKey -Name "WindowsBackupService" -Value "`"$NodePath`" `"$MainJs`" run"
    Write-Host "Registered in Windows Startup Registry: HKCU\Software\Microsoft\Windows\CurrentVersion\Run" -ForegroundColor Green
}
