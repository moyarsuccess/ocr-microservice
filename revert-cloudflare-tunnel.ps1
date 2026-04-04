# ============================================================
#  Cloudflare Tunnel - Full Revert for flutra.ca
#  Run this in PowerShell as Administrator
# ============================================================

$TUNNEL_NAME = "my-services"
$configDir   = "$env:USERPROFILE\.cloudflared"

Write-Host ""
Write-Host "=== Step 1: Stopping the cloudflared Windows service ===" -ForegroundColor Cyan
net stop cloudflared

Write-Host ""
Write-Host "=== Step 2: Uninstalling the Windows service ===" -ForegroundColor Cyan
cloudflared service uninstall

Write-Host ""
Write-Host "=== Step 3: Deleting the tunnel (also cleans up credentials) ===" -ForegroundColor Cyan
# Force-delete any active connections first, then delete the tunnel
cloudflared tunnel cleanup $TUNNEL_NAME
cloudflared tunnel delete $TUNNEL_NAME

Write-Host ""
Write-Host "=== Step 4: Removing local config and credential files ===" -ForegroundColor Cyan
if (Test-Path "$configDir\config.yml") {
    Remove-Item "$configDir\config.yml" -Force
    Write-Host "Removed config.yml" -ForegroundColor Green
}
Get-ChildItem -Path $configDir -Filter "*.json" | Remove-Item -Force
Write-Host "Removed credential .json files" -ForegroundColor Green

Write-Host ""
Write-Host "=== Step 5: DNS records (manual step required) ===" -ForegroundColor Yellow
Write-Host ""
Write-Host "  cloudflared cannot delete DNS records automatically." -ForegroundColor White
Write-Host "  Please delete these two CNAME records manually from your" -ForegroundColor White
Write-Host "  Cloudflare dashboard -> flutra.ca -> DNS:" -ForegroundColor White
Write-Host ""
Write-Host "    ocr.flutra.ca" -ForegroundColor White
Write-Host "    ollama.flutra.ca" -ForegroundColor White
Write-Host ""
Write-Host "  URL: https://dash.cloudflare.com -> flutra.ca -> DNS -> Records" -ForegroundColor Cyan

Write-Host ""
Write-Host "============================================" -ForegroundColor Green
Write-Host " Revert complete." -ForegroundColor Green
Write-Host " Don't forget to delete the DNS records above" -ForegroundColor Yellow
Write-Host " and revert your GoDaddy nameservers if needed." -ForegroundColor Yellow
Write-Host "============================================" -ForegroundColor Green
