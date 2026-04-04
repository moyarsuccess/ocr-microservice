# ============================================================
#  Cloudflare Tunnel Setup for flutra.ca
#  Run this in PowerShell as Administrator
# ============================================================

$DOMAIN    = "flutra.ca"
$OCR_SUB   = "ocr.$DOMAIN"
$OLLAMA_SUB = "ollama.$DOMAIN"
$TUNNEL_NAME = "flutra-services"
$OCR_PORT  = 3001
$OLLAMA_PORT = 11434

Write-Host ""
Write-Host "=== Step 1: Installing cloudflared ===" -ForegroundColor Cyan
winget install Cloudflare.cloudflared
# Refresh PATH so cloudflared is available in this session
$env:Path = [System.Environment]::GetEnvironmentVariable("Path","Machine") + ";" + [System.Environment]::GetEnvironmentVariable("Path","User")

Write-Host ""
Write-Host "=== Step 2: Login to Cloudflare (browser will open) ===" -ForegroundColor Cyan
Write-Host "Log in with the Cloudflare account that manages flutra.ca" -ForegroundColor Yellow
cloudflared tunnel login

Write-Host ""
Write-Host "=== Step 3: Creating tunnel '$TUNNEL_NAME' ===" -ForegroundColor Cyan
$createOutput = cloudflared tunnel create $TUNNEL_NAME 2>&1 | Tee-Object -Variable createOutput
$TUNNEL_ID = ($createOutput | Select-String -Pattern '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}').Matches[0].Value

if (-not $TUNNEL_ID) {
    Write-Host "ERROR: Could not parse tunnel ID from output. Please check the output above." -ForegroundColor Red
    exit 1
}

Write-Host "Tunnel ID: $TUNNEL_ID" -ForegroundColor Green

Write-Host ""
Write-Host "=== Step 4: Writing config file ===" -ForegroundColor Cyan
$configDir = "$env:USERPROFILE\.cloudflared"
New-Item -ItemType Directory -Force -Path $configDir | Out-Null

$config = @"
tunnel: $TUNNEL_ID
credentials-file: $configDir\$TUNNEL_ID.json

ingress:
  - hostname: $OCR_SUB
    service: http://localhost:$OCR_PORT
  - hostname: $OLLAMA_SUB
    service: http://localhost:$OLLAMA_PORT
  - service: http_status:404
"@
Set-Content -Path "$configDir\config.yml" -Value $config
Write-Host "Config written to $configDir\config.yml" -ForegroundColor Green

Write-Host ""
Write-Host "=== Step 5: Creating DNS records for subdomains ===" -ForegroundColor Cyan
cloudflared tunnel route dns $TUNNEL_NAME $OCR_SUB
cloudflared tunnel route dns $TUNNEL_NAME $OLLAMA_SUB

Write-Host ""
Write-Host "=== Step 6: Installing as Windows service (auto-starts on boot) ===" -ForegroundColor Cyan
cloudflared service install
net start cloudflared

Write-Host ""
Write-Host "============================================" -ForegroundColor Green
Write-Host " All done! Your services are now live at:" -ForegroundColor Green
Write-Host "   https://$OCR_SUB    -> localhost:$OCR_PORT" -ForegroundColor White
Write-Host "   https://$OLLAMA_SUB -> localhost:$OLLAMA_PORT" -ForegroundColor White
Write-Host "============================================" -ForegroundColor Green
