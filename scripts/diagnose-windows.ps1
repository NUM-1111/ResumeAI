$ErrorActionPreference = 'Stop'
Write-Host 'Windows command resolution (not WSL):'
Get-Command python3, python, wsl.exe -ErrorAction SilentlyContinue |
    Select-Object Name, CommandType, Source | Format-Table -AutoSize
Write-Host 'Reproduce direct Windows python3 with the Linux script path (--help, no changes):'
$global:LASTEXITCODE = 0
try {
    & python3 /root/java/ResumeAI/scripts/local.py --help
    Write-Host "Direct python3 exit code: $LASTEXITCODE"
} catch {
    Write-Host "Direct python3 error: $($_.Exception.Message)"
}
Write-Host 'Compare explicit Ubuntu Python (--help):'
& wsl.exe -d Ubuntu -u root --cd /root/java/ResumeAI --exec /usr/bin/python3 /root/java/ResumeAI/scripts/local.py --help
Write-Host "Explicit WSL exit code: $LASTEXITCODE"