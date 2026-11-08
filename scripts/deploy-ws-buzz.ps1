# deploy-ws_buzz.ps1 - compila backend e frontend sul PC e li pubblica sul server Docker remoto.
# Vedi DEPLOY_DOCKER_BUILD_PC.md (fasi E, F, G) per la preparazione del server.
#
# Uso:
#   powershell -ExecutionPolicy Bypass -File C:\ws_buzz\ws_buzz_be\scripts\deploy-ws_buzz.ps1
#   powershell -ExecutionPolicy Bypass -File ...\deploy-ws_buzz.ps1 -Domain buzz.esempio.it -SkipTests

param(
  [string]$Server = 'root@195.231.82.182',          # nome definito in ~/.ssh/config (fase A3)
  [string]$Domain = '195-231-82-182.sslip.io',   # dominio pubblico, senza https://
  [string]$Remote = '/opt/ws_buzz',
  [int]$Keep = 5,                           # release da conservare sul server
  [switch]$SkipTests
)

$ErrorActionPreference = 'Stop'

# === Configurazione ===
$Be = 'C:\ws_buzz\ws_buzz_be'
$Fe = 'C:\ws_buzz\ws_buzz'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.2'
$env:Path = 'C:\Program Files\apache-maven-3.8.6\bin;' + $env:JAVA_HOME + '\bin;' + $env:Path

if ($Domain -eq 'buzz.tuodominio.it') {
  throw "Imposta il dominio: modifica il default di -Domain nello script o passa -Domain <dominio>"
}

function Invoke-Step($Dir, [scriptblock]$Block, $ErrorMessage) {
  Push-Location $Dir
  try {
    & $Block
    if ($LASTEXITCODE -ne 0) { throw $ErrorMessage }
  } finally {
    Pop-Location
  }
}

# === 0. Controllo connessione al server ===
Write-Host "`n=== Controllo connessione a $Server ===" -ForegroundColor Cyan
ssh -o BatchMode=yes -o ConnectTimeout=10 $Server "test -f $Remote/docker-compose.yml"
if ($LASTEXITCODE -ne 0) { throw "Server non raggiungibile via SSH o $Remote/docker-compose.yml mancante (fasi A3, F2)" }

# === 1. Build backend ===
Write-Host "`n=== Build backend ===" -ForegroundColor Cyan
$mvnArgs = @('-B', '-q', 'clean', 'package')
if ($SkipTests) { $mvnArgs += '-DskipTests' }
Invoke-Step $Be { mvn @mvnArgs } 'Build backend fallita'

# === 2. Build frontend ===
Write-Host "`n=== Build frontend ===" -ForegroundColor Cyan
Invoke-Step $Fe { npm ci } 'npm ci fallito'
Invoke-Step $Fe { npx ng build } 'Build frontend fallita'

# === 3. Pacchetto della release ===
Write-Host "`n=== Pacchetto ===" -ForegroundColor Cyan
$Release = Get-Date -Format 'yyyyMMdd-HHmmss'
$Stage = Join-Path $env:TEMP "ws_buzz-$Release"
$Tgz = "$Stage.tgz"
New-Item -ItemType Directory $Stage | Out-Null

$Jar = @(Get-ChildItem "$Be\target\*.jar")
if ($Jar.Count -ne 1) { throw "Atteso un solo jar in target, trovati $($Jar.Count)" }
Copy-Item $Jar[0].FullName "$Stage\app.jar"
Copy-Item -Recurse "$Fe\dist\ws-buzz\browser" "$Stage\browser"
Copy-Item "$Fe\nginx.conf" "$Stage\nginx.conf"

function Get-GitInfo($Dir) {
  $hash = git -C $Dir rev-parse --short HEAD
  $dirty = if (git -C $Dir status --porcelain) { ' (+ modifiche non committate)' } else { '' }
  "$hash$dirty"
}
@(
  "release:  $Release",
  "backend:  $(Get-GitInfo $Be)",
  "frontend: $(Get-GitInfo $Fe)"
) | Set-Content -Encoding ascii "$Stage\RELEASE.txt"
Get-Content "$Stage\RELEASE.txt"

tar -czf $Tgz -C $Stage .
if ($LASTEXITCODE -ne 0) { throw 'Creazione archivio fallita' }

# === 4. Upload e attivazione ===
Write-Host "`n=== Upload e attivazione su $Server ===" -ForegroundColor Cyan
scp $Tgz "${Server}:/tmp/ws_buzz-$Release.tgz"
if ($LASTEXITCODE -ne 0) { throw 'Upload fallito' }

$cmd = "set -e; " +
       "d=$Remote/releases/$Release; mkdir -p `$d; " +
       "tar -xzf /tmp/ws_buzz-$Release.tgz -C `$d; rm /tmp/ws_buzz-$Release.tgz; " +
       "chmod -R a+rX `$d; " +
       "ln -sfn releases/$Release $Remote/current; " +
       "cd $Remote; " +
       "docker compose up -d --force-recreate backend frontend; " +
       "docker compose up -d; " +
       "ls -1 releases | sort -r | tail -n +$($Keep + 1) | while read r; do rm -rf releases/`$r; done; " +
       "docker compose ps"
ssh $Server $cmd
if ($LASTEXITCODE -ne 0) { throw 'Attivazione sul server fallita' }
Remove-Item -Recurse -Force $Stage, $Tgz

# === 5. Verifica ===
Write-Host "`n=== Verifica https://$Domain/api/health ===" -ForegroundColor Cyan
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$health = $null
for ($i = 0; $i -lt 30 -and -not $health; $i++) {
  Start-Sleep -Seconds 3
  try { $health = Invoke-RestMethod "https://$Domain/api/health" } catch { }
}
if (-not $health) { throw "Il sito non risponde dopo 90 s: sul server controlla 'docker compose logs backend caddy'" }
Write-Host "status: $($health.status)   version: $($health.version)   buildTime: $($health.buildTime)"
if ($health.status -ne 'OK') { $health.checks | ConvertTo-Json }
Write-Host "`nRelease $Release attiva." -ForegroundColor Green
