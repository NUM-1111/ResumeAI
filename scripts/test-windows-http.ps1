$ErrorActionPreference = 'Stop'
for ($attempt = 0; $attempt -lt 15; $attempt++) {
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:8080/login' -TimeoutSec 2
        if ($response.StatusCode -eq 200 -and $response.Content.Contains('password')) {
            Write-Host 'PASS: Windows host can access WSL login page at http://127.0.0.1:8080/login'
            exit 0
        }
    } catch { Start-Sleep -Seconds 1 }
}
throw 'Windows host HTTP verification failed; do not assume localhost forwarding works.'