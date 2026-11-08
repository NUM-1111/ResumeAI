param(
    [ValidateSet('db-start', 'db-stop', 'frontend', 'verify', 'run', 'dependencies', 'status')]
    [string]$Command = 'status'
)
$ErrorActionPreference = 'Stop'
if (-not (Get-Command wsl.exe -ErrorAction SilentlyContinue)) {
    throw 'wsl.exe is unavailable. This project runs in Ubuntu WSL, not Windows Python.'
}
Write-Host "AgentCV: Ubuntu WSL / $Command (not Windows Python)"
& wsl.exe -d Ubuntu -u root --cd /root/java/ResumeAI --exec /usr/bin/python3 /root/java/ResumeAI/scripts/local.py $Command
$code = $LASTEXITCODE
if ($code -ne 0) {
    Write-Host "AgentCV command failed (exit $code). See the error above; do not treat it as success." -ForegroundColor Red
}
exit $code