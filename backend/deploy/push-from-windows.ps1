# Subir backend al VPS desde Windows
# Uso: .\push-from-windows.ps1 -Server root@TU_IP
param(
    [Parameter(Mandatory = $true)]
    [string]$Server,
    [string]$RemotePath = "/var/www/zup"
)

$Root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$Backend = Join-Path $Root "backend"

Write-Host "Subiendo backend a ${Server}:${RemotePath} ..."

# Crear tarball excluyendo basura
$Temp = Join-Path $env:TEMP "zuppon-backend.tar.gz"
if (Test-Path $Temp) { Remove-Item $Temp }

Push-Location $Backend
tar --exclude=venv --exclude=__pycache__ --exclude=*.pyc --exclude=zuppon.db -czf $Temp .
Pop-Location

scp $Temp "${Server}:/tmp/zuppon-backend.tar.gz"
ssh $Server @"
set -e
mkdir -p $RemotePath/backend
tar -xzf /tmp/zuppon-backend.tar.gz -C $RemotePath/backend
rm /tmp/zuppon-backend.tar.gz
bash $RemotePath/backend/deploy/update.sh
"@

Write-Host "Listo. Verificar: curl https://institutocaacupepy.es/health"
