# Deploy di ws-buzz con Docker su Ubuntu — build sul PC

Guida completa per pubblicare backend (`ws_buzz_be`) e frontend (`ws_buzz`) su un server Ubuntu con Docker: dalla macchina appena creata fino all'app online in HTTPS, più aggiornamenti, rollback e risoluzione dei problemi.

**Come funziona il deploy:** compili backend e frontend **sul tuo PC**, e sul server carichi solo i file già pronti (jar, app Angular compilata, configurazione Nginx). Sul server **non si compila nulla**: i container usano le immagini ufficiali di Java, Nginx e Caddy e leggono i file caricati.

> Esiste anche la guida alternativa [DEPLOY_DOCKER_BUILD_SERVER.md](DEPLOY_DOCKER_BUILD_SERVER.md), in cui il server scarica il codice da GitHub e compila dentro Docker con i Dockerfile dei repository.

> Il `docker-compose.yml`, il `Caddyfile`, la configurazione Nginx e lo script di deploy di questa guida **non sono ancora stati provati** (Docker non era disponibile sulla macchina di sviluppo). Al primo deploy segui le verifiche della fase H, e in caso di errori consulta il §12.

Nei comandi, sostituisci sempre:

| Segnaposto | Cosa mettere |
|---|---|
| `buzz.tuodominio.it` | il dominio (o sottodominio) del sito |
| `IP_DEL_SERVER` | l'IP pubblico del server |
| `tua@email.it` | la tua email (Let's Encrypt la usa per gli avvisi di scadenza dei certificati) |
| `deploy` | il nome dell'utente Linux che userai sul server (puoi tenere questo) |
| `buzz-server` | il nome breve con cui il PC si collega al server (fase A3; puoi tenere questo) |

---

## Indice

1. [Architettura](#1-architettura)
2. [Cosa serve prima di iniziare](#2-cosa-serve-prima-di-iniziare)
3. [Fase A — Preparare il PC](#3-fase-a--preparare-il-pc)
4. [Fase B — Preparare il server Ubuntu](#4-fase-b--preparare-il-server-ubuntu)
5. [Fase C — Installare Docker](#5-fase-c--installare-docker)
6. [Fase D — Configurare il DNS](#6-fase-d--configurare-il-dns)
7. [Fase E — Cartelle sul server](#7-fase-e--cartelle-sul-server)
8. [Fase F — File di configurazione del deploy](#8-fase-f--file-di-configurazione-del-deploy)
9. [Fase G — Script di deploy e prima release](#9-fase-g--script-di-deploy-e-prima-release)
10. [Fase H — Verifiche](#10-fase-h--verifiche)
11. [Gestione quotidiana: nuove release, rollback, log, pulizia, backup](#11-gestione-quotidiana)
12. [Risoluzione dei problemi](#12-risoluzione-dei-problemi)
13. [Riferimento: configurazione di backend e frontend](#13-riferimento-configurazione-di-backend-e-frontend)
14. [Variante: sul server c'è già un reverse proxy](#14-variante-sul-server-cè-già-un-reverse-proxy)
15. [Cose da NON fare](#15-cose-da-non-fare)

---

## 1. Architettura

```
                         PC Windows                                       Server Ubuntu
 ┌──────────────────────────────────────────────┐           ┌──────────────────────────────────────────┐
 │ mvn package     → app.jar                    │   scp     │ /opt/ws-buzz/releases/20261011-153000/   │
 │ ng build        → browser/ (app compilata)   │ ────────▶ │   app.jar  browser/  nginx.conf          │
 │ nginx.conf (dal repo ws_buzz)                │  (.tgz)   │ /opt/ws-buzz/current ──▶ ultima release  │
 └──────────────────────────────────────────────┘           └──────────────────────────────────────────┘

Internet ──HTTPS──▶ caddy (443/80) ──HTTP──▶ frontend (nginx) ──┬── file statici Angular   (current/browser)
                                                                 ├── /api/* ──▶ backend :8080 (current/app.jar)
                                                                 └── /ws    ──▶ backend :8080 (WebSocket)
```

- **3 container**, tutti da immagini ufficiali (nessuna immagine da costruire):
  - `caddy` (`caddy:2-alpine`) gestisce l'HTTPS e ottiene e rinnova da solo il certificato Let's Encrypt;
  - `frontend` (`nginx:stable-alpine`) serve l'app Angular e inoltra `/api` e `/ws` al backend;
  - `backend` (`eclipse-temurin:21-jre`) esegue il jar Spring Boot.
- **Release**: ogni deploy crea una cartella `releases/<data-ora>` con i file caricati. Il collegamento `current` punta a quella attiva, e i container leggono i file da `current`. Per tornare indietro basta spostare `current` su una release precedente.
- **Un solo dominio pubblico.** Il frontend chiama il backend con URL relativi (`/api`, `/ws`), quindi la build del frontend è identica in ogni ambiente e per il browser non ci sono richieste CORS.
- **Solo `caddy` espone porte sull'host** (80 e 443). `frontend` e `backend` sono raggiungibili solo dalla rete interna di Docker.
- **Nessun database**: lo stato delle stanze è in memoria nel backend. Riavviare il backend chiude tutte le stanze attive.

---

## 2. Cosa serve prima di iniziare

### Server

| Cosa | Dettagli |
|---|---|
| **Sistema** | Ubuntu Server **24.04 LTS** (va bene anche 22.04). Architettura x86_64 oppure ARM64: jar e app Angular non dipendono dall'architettura. |
| **RAM** | 1 GB è sufficiente, 2 GB è più tranquillo. Sul server non si compila, quindi non servono grandi risorse. |
| **Disco** | Almeno **10 GB** liberi (immagini Docker, release, log). |
| **Accesso SSH** | IP del server e credenziali iniziali date dal provider (utente `root` con password o chiave). |

### Dominio

Un dominio o sottodominio di cui puoi gestire il DNS (es. `buzz.tuodominio.it`). Senza dominio non si ottiene il certificato HTTPS (vedi il §12, "Non ho un dominio").

### PC Windows (dove compili)

| Strumento | Percorso / versione | Serve per |
|---|---|---|
| JDK 21 | `C:\Program Files\Java\jdk-21.0.2` | compilare il backend |
| Maven 3.8+ | `C:\Program Files\apache-maven-3.8.6` | compilare il backend |
| Node.js + npm | Node 20.19+, 22.12+ o 24 | compilare il frontend |
| OpenSSH client (`ssh`, `scp`) | incluso in Windows 10/11 | caricare i file sul server |
| `tar` | incluso in Windows 10/11 | creare l'archivio della release |
| I due progetti | `C:\ws_buzz\ws_buzz_be`, `C:\ws_buzz\ws_buzz` | sorgenti |

Il jar va compilato con **Java 21**: il container del backend usa Java 21 e un jar compilato con una versione più recente non parte.

---

## 3. Fase A — Preparare il PC

Comandi in **PowerShell** sul PC.

### A1. Verificare gli strumenti

```powershell
& 'C:\Program Files\Java\jdk-21.0.2\bin\java.exe' -version     # deve dire 21.x
& 'C:\Program Files\apache-maven-3.8.6\bin\mvn.cmd' -version    # Apache Maven 3.8.x
node --version                                                  # v20.19+, v22.12+ o v24.x
ssh -V
tar --version                                                   # bsdtar ...
```

Se `ssh` non viene trovato: Impostazioni → Sistema → Funzionalità facoltative → aggiungi **Client OpenSSH**.

### A2. Creare una chiave SSH (se non ce l'hai già)

```powershell
Test-Path $env:USERPROFILE\.ssh\id_ed25519.pub
# se risponde False:
ssh-keygen -t ed25519 -C "pc-windows"
```

Premi Invio per il percorso di default e imposta una passphrase.

### A3. Nome breve per il server

Lo script di deploy usa il nome `buzz-server` invece di utente e IP. Crea la configurazione SSH (sostituisci `IP_DEL_SERVER`):

```powershell
New-Item -ItemType Directory -Force $env:USERPROFILE\.ssh | Out-Null
Add-Content -Encoding ascii -Path $env:USERPROFILE\.ssh\config -Value @'

Host buzz-server
    HostName IP_DEL_SERVER
    User deploy
    IdentityFile ~/.ssh/id_ed25519
    ServerAliveInterval 30
'@
```

Non creare il file con Blocco note: rischia di salvarlo come `config.txt`, e SSH lo ignorerebbe.

Il nome `buzz-server` funzionerà solo dopo la fase B4, quando l'utente `deploy` esiste.

### A4. Non digitare la passphrase a ogni comando (facoltativo)

Lo script esegue più comandi `ssh`/`scp`. Con l'agente SSH la passphrase si inserisce una sola volta. Da PowerShell **come amministratore**:

```powershell
Get-Service ssh-agent | Set-Service -StartupType Automatic
Start-Service ssh-agent
```

Poi, da PowerShell normale:

```powershell
ssh-add $env:USERPROFILE\.ssh\id_ed25519
```

### A5. File del frontend usati dal deploy

Il deploy carica sul server il file **`C:\ws_buzz\ws_buzz\nginx.conf`** del repo frontend: è la configurazione del container Nginx (vedi §13). Deve esistere. Se lo modifichi, la modifica arriva sul server con la release successiva.

Il deploy pubblica i file **così come sono sul disco**, anche se non committati. È buona pratica fare commit prima di ogni deploy: lo script registra in ogni release il commit di partenza e se c'erano modifiche non committate (`RELEASE.txt`).

---

## 4. Fase B — Preparare il server Ubuntu

Comandi da eseguire **sul server**, via SSH, salvo dove indicato "sul PC".

### B1. Primo accesso

Sul PC:

```powershell
ssh root@IP_DEL_SERVER
```

Alla prima connessione rispondi `yes` alla domanda sull'impronta (fingerprint) del server.

### B2. Aggiornare il sistema

```bash
apt update && apt full-upgrade -y
apt install -y curl ca-certificates dnsutils ufw
reboot
```

Dopo il riavvio aspetta un minuto e ricollegati con `ssh root@IP_DEL_SERVER`.

### B3. Creare l'utente di lavoro

Non lavorare come `root`. Sul server:

```bash
adduser deploy                 # chiede una password: sceglila robusta, servirà per sudo
usermod -aG sudo deploy
```

### B4. Copiare la chiave SSH sull'utente

Sul PC:

```powershell
type $env:USERPROFILE\.ssh\id_ed25519.pub | ssh root@IP_DEL_SERVER "mkdir -p /home/deploy/.ssh && cat >> /home/deploy/.ssh/authorized_keys && chown -R deploy:deploy /home/deploy/.ssh && chmod 700 /home/deploy/.ssh && chmod 600 /home/deploy/.ssh/authorized_keys"
```

**Prova subito** ad accedere con il nuovo utente usando il nome breve della fase A3, da una **seconda** finestra PowerShell, lasciando aperta quella di root:

```powershell
ssh buzz-server
sudo whoami      # deve rispondere: root
```

Prosegui solo se questo funziona.

### B5. Mettere in sicurezza SSH

Sul server, come `deploy`:

```bash
sudo tee /etc/ssh/sshd_config.d/00-hardening.conf > /dev/null <<'EOF'
PermitRootLogin no
PasswordAuthentication no
KbdInteractiveAuthentication no
EOF
sudo sshd -t && sudo systemctl restart ssh
```

- Il nome inizia con `00-` di proposito. In SSH vince il **primo** valore letto, e alcune immagini cloud contengono `50-cloud-init.conf` con `PasswordAuthentication yes`: il file `00-` viene letto prima e quindi prevale.
- `sshd -t` controlla la sintassi. Se stampa errori, **non** riavviare SSH.
- **Prima di chiudere la sessione aperta**, prova un nuovo accesso da un'altra finestra (`ssh buzz-server`). Se non entri, hai ancora la vecchia sessione per correggere.

### B6. Swap (consigliato con 1 GB di RAM)

```bash
swapon --show          # se mostra già uno swap, salta questo passo
sudo fallocate -l 1G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
free -h                # la riga "Swap" deve mostrare 1.0Gi
```

È una riserva di sicurezza: evita che il sistema termini un processo se la memoria si esaurisce per un momento.

### B7. Fuso orario e ora

```bash
sudo timedatectl set-timezone Europe/Rome
timedatectl            # "System clock synchronized: yes" e "NTP service: active"
```

Un orologio sbagliato fa fallire l'emissione dei certificati HTTPS.

### B8. Firewall

```bash
sudo ufw allow OpenSSH          # PRIMA di abilitare il firewall, altrimenti perdi l'accesso SSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw allow 443/udp          # HTTP/3
sudo ufw enable
sudo ufw status verbose
```

Due cose da sapere:
- **Docker scavalca `ufw`**: le porte pubblicate da un container (`ports:` nel compose) sono raggiungibili da Internet **anche se `ufw` non le consente**. Per questo nel compose solo Caddy pubblica porte, e la 8080 del backend non è mai pubblicata.
- Molti provider (AWS, Oracle, Hetzner, OVH, Azure…) hanno un **firewall proprio nel pannello web** (security group / cloud firewall). Anche lì vanno aperte le porte 22, 80, 443/tcp e 443/udp.

### B9. Aggiornamenti di sicurezza automatici

Ubuntu Server ha di solito `unattended-upgrades` già attivo. Per verificarlo:

```bash
sudo apt install -y unattended-upgrades
sudo dpkg-reconfigure -plow unattended-upgrades     # rispondi "Yes"
```

### B10. Controllare che le porte 80 e 443 siano libere

```bash
sudo ss -ltnp | grep -E ':(80|443) '
```

Non deve uscire nulla. Se compaiono `apache2` o `nginx` installati sul sistema:

```bash
sudo systemctl disable --now apache2 nginx 2>/dev/null
```

Se invece quel server web ti serve per altri siti, usa la variante del §14.

---

## 5. Fase C — Installare Docker

Va installato dal **repository ufficiale di Docker**. Il pacchetto `docker.io` di Ubuntu e lo snap sono versioni diverse e non vanno usati.

### C1. Rimuovere eventuali versioni non ufficiali

```bash
for pkg in docker.io docker-doc docker-compose docker-compose-v2 podman-docker containerd runc; do sudo apt-get remove -y $pkg; done
sudo snap remove docker 2>/dev/null || true
```

Se segnala che i pacchetti non sono installati va bene.

### C2. Aggiungere il repository Docker e installare

```bash
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc

echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}") stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
```

### C3. Verificare

```bash
sudo docker run --rm hello-world     # deve stampare "Hello from Docker!"
docker compose version               # deve rispondere "Docker Compose version v2.x"
```

Si usa `docker compose` (con lo spazio). Il vecchio `docker-compose` (con il trattino) è la versione 1, obsoleta.

### C4. Usare Docker senza `sudo`

Lo script di deploy esegue `docker compose` come utente `deploy`, quindi questo passo è **obbligatorio**.

```bash
sudo usermod -aG docker deploy
exit
```

Ricollegati (`ssh buzz-server`) perché il gruppo abbia effetto, poi prova:

```bash
docker ps        # non deve dare "permission denied"
```

Far parte del gruppo `docker` equivale ad avere i permessi di root: aggiungi solo utenti fidati.

### C5. Rotazione dei log (da fare PRIMA del primo avvio)

Di default Docker conserva i log dei container senza limiti, e col tempo riempiono il disco.

```bash
sudo tee /etc/docker/daemon.json > /dev/null <<'EOF'
{
  "log-driver": "local",
  "log-opts": {
    "max-size": "10m",
    "max-file": "5"
  }
}
EOF
sudo systemctl restart docker
docker info --format '{{.LoggingDriver}}'    # deve rispondere: local
```

La configurazione vale solo per i container **creati dopo** questa modifica. Per questo va fatta prima della fase G.

### C6. Avvio automatico al boot

```bash
sudo systemctl enable docker containerd
systemctl is-enabled docker      # enabled
```

### C7. Scaricare in anticipo le immagini (facoltativo)

Fa risparmiare tempo al primo deploy:

```bash
docker pull eclipse-temurin:21-jre
docker pull nginx:stable-alpine
docker pull caddy:2-alpine
```

---

## 6. Fase D — Configurare il DNS

Nel pannello del registrar o del gestore DNS del dominio:

| Tipo | Nome | Valore | TTL |
|---|---|---|---|
| `A` | `buzz` (oppure `@` se usi il dominio principale) | `IP_DEL_SERVER` | 300 |

- **IPv6 (record `AAAA`)**: aggiungilo **solo** se il server ha un IPv6 funzionante. Un `AAAA` che punta a un indirizzo non raggiungibile fa fallire il certificato e blocca chi naviga in IPv6. Nel dubbio, metti solo il record `A`.
- **Cloudflare**: se il DNS è su Cloudflare, imposta il record su **"DNS only"** (nuvola grigia), almeno finché il certificato non è stato emesso. Con il proxy arancione serve una configurazione SSL apposita ("Full (strict)").

Verifica dal server, dopo qualche minuto:

```bash
curl -4 -s ifconfig.me; echo           # IP pubblico del server
dig +short buzz.tuodominio.it          # deve restituire LO STESSO IP
```

**Non passare alla fase G finché i due IP non coincidono.** Altrimenti Caddy chiede il certificato, fallisce, e dopo alcuni tentativi Let's Encrypt lo blocca temporaneamente (limite di frequenza).

---

## 7. Fase E — Cartelle sul server

```bash
sudo mkdir -p /opt/ws-buzz/releases
sudo chown -R deploy:deploy /opt/ws-buzz
```

Struttura finale (le release vengono create dallo script di deploy):

```
/opt/ws-buzz/
├── .env                      # dominio ed email (fase F1)
├── docker-compose.yml        # fase F2
├── Caddyfile                 # fase F3
├── current -> releases/20261011-153000     # collegamento alla release attiva
└── releases/
    ├── 20261011-153000/
    │   ├── app.jar           # backend
    │   ├── browser/          # frontend compilato (index.html, *.js, *.css)
    │   ├── nginx.conf        # configurazione Nginx (dal repo ws_buzz)
    │   └── RELEASE.txt       # data e commit di partenza
    └── 20261009-201500/      # release precedenti (per il rollback)
```

**Non creare a mano** la cartella `current`: è un collegamento simbolico creato dallo script.

---

## 8. Fase F — File di configurazione del deploy

Questi file stanno **solo sul server**, in `/opt/ws-buzz`, e si creano una volta sola.

### F1. `.env`

```bash
cat > /opt/ws-buzz/.env <<'EOF'
DOMAIN=buzz.tuodominio.it
ACME_EMAIL=tua@email.it
EOF
chmod 600 /opt/ws-buzz/.env
```

Scrivi solo il dominio: niente `https://`, niente `/` finale, niente spazi.

### F2. `docker-compose.yml`

```bash
nano /opt/ws-buzz/docker-compose.yml
```

Incolla il contenuto seguente, poi salva con `Ctrl+O`, `Invio` ed esci con `Ctrl+X`:

```yaml
name: ws-buzz

services:
  backend:
    image: eclipse-temurin:21-jre
    restart: unless-stopped
    user: "65534:65534"            # utente senza privilegi (nobody)
    working_dir: /tmp
    command: ["java", "-jar", "/release/app.jar"]
    environment:
      # Ricavata dal dominio: e' l'unica origine da cui il browser chiama il backend.
      APP_CORS_ALLOWED_ORIGINS: https://${DOMAIN:?imposta DOMAIN nel file .env}
      JAVA_TOOL_OPTIONS: -XX:MaxRAMPercentage=75
      TZ: Europe/Rome
    volumes:
      - type: bind
        source: ./current/app.jar
        target: /release/app.jar
        read_only: true
        bind:
          create_host_path: false  # se manca la release, errore invece di creare cartelle vuote
    mem_limit: 512m
    # nessuna "ports": raggiungibile solo dalla rete interna di Docker

  frontend:
    image: nginx:stable-alpine
    restart: unless-stopped
    depends_on:
      - backend
    volumes:
      - type: bind
        source: ./current/browser
        target: /usr/share/nginx/html
        read_only: true
        bind:
          create_host_path: false
      - type: bind
        source: ./current/nginx.conf
        target: /etc/nginx/conf.d/default.conf
        read_only: true
        bind:
          create_host_path: false
    mem_limit: 128m
    # "healthy" = Nginx risponde E il backend e' raggiungibile attraverso di lui.
    healthcheck:
      test: ["CMD", "wget", "-q", "-O", "/dev/null", "http://127.0.0.1/api/health"]
      interval: 30s
      timeout: 5s
      retries: 3
      start_period: 60s

  caddy:
    image: caddy:2-alpine
    restart: unless-stopped
    ports:
      - "80:80"
      - "443:443"
      - "443:443/udp"
    environment:
      DOMAIN: ${DOMAIN:?imposta DOMAIN nel file .env}
      ACME_EMAIL: ${ACME_EMAIL:?imposta ACME_EMAIL nel file .env}
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - caddy_data:/data
      - caddy_config:/config
    depends_on:
      - frontend

volumes:
  caddy_data:     # certificati HTTPS: NON cancellarlo (vedi §15)
  caddy_config:
```

Note:
- I file della release sono montati in **sola lettura**: i container non possono modificarli.
- Docker risolve il collegamento `current` **quando crea il container**. Per questo, dopo aver cambiato release, lo script ricrea i container (`--force-recreate`): un semplice `restart` continuerebbe a usare la release vecchia.
- 512 MB per il backend sono abbondanti per qualche decina di giocatori. Con `MaxRAMPercentage=75` l'heap Java arriva al massimo a circa 384 MB.
- **Non** aggiungere `replicas` né scalare il backend: deve esistere una sola istanza.
- **Non avviare** il compose a mano prima della prima release: i file di `current` non esistono ancora e l'avvio fallisce (vedi §12).

### F3. `Caddyfile`

```bash
cat > /opt/ws-buzz/Caddyfile <<'EOF'
{
    email {$ACME_EMAIL}
}

{$DOMAIN} {
    encode zstd gzip
    reverse_proxy frontend:80
}
EOF
```

Caddy:
- ottiene e rinnova da solo il certificato HTTPS;
- reindirizza HTTP → HTTPS;
- gestisce il WebSocket senza configurazioni aggiuntive;
- passa a Nginx gli header `X-Forwarded-For` e `X-Forwarded-Proto`.

### F4. Controllare la configurazione

```bash
cd /opt/ws-buzz
docker compose config | grep -E 'APP_CORS|DOMAIN|ACME'
```

Deve mostrare `APP_CORS_ALLOWED_ORIGINS: https://buzz.tuodominio.it` e i valori giusti. Un errore `imposta DOMAIN nel file .env` significa che `.env` manca o è scritto male.

---

## 9. Fase G — Script di deploy e prima release

Il deploy si fa **dal PC** con uno script PowerShell. Lo script:
1. compila il backend, test compresi;
2. compila il frontend;
3. prepara un archivio con `app.jar`, `browser/`, `nginx.conf` e `RELEASE.txt`;
4. lo carica sul server e lo estrae in `releases/<data-ora>`;
5. sposta `current` sulla nuova release e ricrea i container;
6. elimina le release più vecchie, conservando le ultime 5;
7. aspetta che il sito risponda e mostra l'esito di `/api/health`.

### G1. Creare lo script

Salvalo **fuori dai repository**, per esempio in `C:\ws_buzz\deploy-ws-buzz.ps1`, e modifica la sezione "Configurazione":

```powershell
# deploy-ws-buzz.ps1 - compila backend e frontend sul PC e li pubblica sul server.
# Uso: powershell -ExecutionPolicy Bypass -File C:\ws_buzz\deploy-ws-buzz.ps1

$ErrorActionPreference = 'Stop'

# === Configurazione ===
$Server = 'buzz-server'                 # nome definito in ~/.ssh/config (fase A3)
$Domain = 'buzz.tuodominio.it'
$Remote = '/opt/ws-buzz'
$Keep   = 5                             # release da conservare sul server
$Be     = 'C:\ws_buzz\ws_buzz_be'
$Fe     = 'C:\ws_buzz\ws_buzz'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.2'
$env:Path = 'C:\Program Files\apache-maven-3.8.6\bin;' + $env:JAVA_HOME + '\bin;' + $env:Path

# === 1. Build backend (con test) ===
Write-Host "`n=== Build backend ===" -ForegroundColor Cyan
Push-Location $Be
mvn -B -q clean package
if ($LASTEXITCODE -ne 0) { throw 'Build backend fallita' }
Pop-Location

# === 2. Build frontend ===
Write-Host "`n=== Build frontend ===" -ForegroundColor Cyan
Push-Location $Fe
npm ci
if ($LASTEXITCODE -ne 0) { throw 'npm ci fallito' }
npx ng build
if ($LASTEXITCODE -ne 0) { throw 'Build frontend fallita' }
Pop-Location

# === 3. Pacchetto della release ===
Write-Host "`n=== Pacchetto ===" -ForegroundColor Cyan
$Release = Get-Date -Format 'yyyyMMdd-HHmmss'
$Stage = Join-Path $env:TEMP "ws-buzz-$Release"
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
scp $Tgz "${Server}:/tmp/ws-buzz-$Release.tgz"
if ($LASTEXITCODE -ne 0) { throw 'Upload fallito' }

$cmd = "set -e; " +
       "d=$Remote/releases/$Release; mkdir -p `$d; " +
       "tar -xzf /tmp/ws-buzz-$Release.tgz -C `$d; rm /tmp/ws-buzz-$Release.tgz; " +
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
```

### G2. Prima di lanciarlo

- **Ferma il backend e il frontend locali** (`/run-be`, `ng serve`). Su Windows i file in uso bloccano `mvn clean` e `npm ci`.
- La fase D è completata: il DNS punta al server.
- Le fasi C4 (gruppo `docker`), E e F sono completate.

### G3. Prima release

Sul PC:

```powershell
powershell -ExecutionPolicy Bypass -File C:\ws_buzz\deploy-ws-buzz.ps1
```

`-ExecutionPolicy Bypass` serve perché Windows di default blocca gli script `.ps1` scaricati o creati a mano.

Durata: 2–5 minuti (build più upload di circa 25 MB). Al primo avvio il server scarica anche le immagini Docker, se non l'hai fatto nella fase C7.

Alla fine deve comparire:

```
status: OK   version: 0.0.1-SNAPSHOT   buildTime: 2026-...
Release 20261011-153000 attiva.
```

Se la verifica finale fallisce **al primo deploy**, di solito Caddy sta ancora ottenendo il certificato: aspetta un minuto e apri `https://buzz.tuodominio.it/api/health` dal browser.

### G4. Log del primo avvio

Sul server:

```bash
cd /opt/ws-buzz
docker compose ps                       # backend, frontend e caddy "running"; frontend "healthy" entro ~1 minuto
docker compose logs backend | tail -n 30
# deve comparire:  No active profile set ...
#                  Started WsBuzzBackendApplication in ... seconds
docker compose logs caddy | grep -iE 'certificate obtained|error'
# deve comparire:  certificate obtained successfully   (per buzz.tuodominio.it)
readlink current                        # releases/<data-ora> della release appena caricata
```

Se nei log di Caddy compaiono errori, vedi il §12 **prima** di riprovare, per non incorrere nel limite di frequenza di Let's Encrypt.

---

## 10. Fase H — Verifiche

1. **HTTPS**: apri `https://buzz.tuodominio.it`. Il lucchetto dev'essere valido e la home dell'app deve caricarsi.
2. **Redirect**: apri `http://buzz.tuodominio.it` (senza la s): deve reindirizzare a `https://`.
3. **Diagnostica**: apri `https://buzz.tuodominio.it/api/health`. Deve risultare `"status":"OK"`. Altrimenti, in `checks` trovi quale controllo è fallito:

   | Campo | Valore atteso | Se è sbagliato |
   |---|---|---|
   | `checks.cors` | `ok: true` | `APP_CORS_ALLOWED_ORIGINS` non coincide con `request.origin`: controlla `DOMAIN` in `.env` (es. `www.` mancante) |
   | `checks.https` | `ok: true`, `request.scheme` = `https` | Nginx non inoltra `X-Forwarded-Proto`, oppure le reti Docker non sono in un intervallo IP privato (vedi §13) |
   | `checks.profile` | `ok: true`, `activeProfiles` = `[]` | È impostato `SPRING_PROFILES_ACTIVE=dev`: toglilo |
   | `request.clientIp` | il tuo IP pubblico | Se compare un IP `172.x`, `X-Forwarded-For` non viene inoltrato (non blocca il funzionamento) |
   | `version` / `buildTime` | quelli dell'ultima build sul PC | `current` non punta alla release nuova, o i container non sono stati ricreati: `readlink current` e `docker compose up -d --force-recreate backend frontend` |
   | `webSocketSessions` | `N current WS(N)…` con N > 0 mentre qualcuno è in una stanza | Il WebSocket non arriva al backend: controlla la rotta `/ws` in `nginx.conf` |

4. **WebSocket**: crea una stanza. In DevTools (F12) → Network → filtro **WS** deve esserci `wss://buzz.tuodominio.it/ws` con stato `101`.
5. **QR code**: dal telefono, in **rete mobile** (non in Wi-Fi, così provi davvero dall'esterno), scansiona il QR code. La pagina `/room/XXXX/play` si deve aprire, non un 404.
6. **Refresh**: ricarica (F5) la pagina host. Deve riaprirsi senza 404.
7. **Partita di prova**: con 2–3 dispositivi prova buzz, reset, lock, cambio modalità e punteggio.
8. **Connessione inattiva**: lascia la stanza aperta più di 2 minuti senza fare nulla. Il WebSocket non deve cadere: in DevTools la connessione resta aperta e continuano ad arrivare frame di heartbeat.
9. **Errori** (facoltativo): quando il server rifiuta un'azione, l'app mostra un toast rosso in basso con il messaggio. Dall'interfaccia è difficile provocarlo apposta. Se durante le prove compare, il canale degli errori funziona. Se un'azione non ha effetto e il toast non compare, controlla `docker compose logs backend` (righe `WARN ... Operazione rifiutata` o `Stanza non trovata`).
10. **Riavvio del server**: `sudo reboot`. Dopo un paio di minuti, con `ssh buzz-server` e poi `cd /opt/ws-buzz && docker compose ps`, tutti i servizi devono essere di nuovo `running` e il sito deve funzionare.
11. **Porte chiuse**: dal PC, `Test-NetConnection IP_DEL_SERVER -Port 8080` deve dare `TcpTestSucceeded : False`.
12. **Rollback**: prova una volta la procedura del §11 "Tornare a una release precedente". Così, se servirà davvero, sai già che funziona.

---

## 11. Gestione quotidiana

### Pubblicare una nuova versione

**Ogni deploy riavvia il backend e chiude tutte le stanze attive**: fallo quando nessuno sta giocando.

1. Sul PC, ferma `/run-be` e `ng serve` se sono attivi.
2. Facoltativo ma consigliato: commit delle modifiche.
3. Lancia lo script:

   ```powershell
   powershell -ExecutionPolicy Bypass -File C:\ws_buzz\deploy-ws-buzz.ps1
   ```

4. Controlla che `buildTime` stampato alla fine sia quello appena prodotto.

Se una build o un test falliscono, lo script si ferma **prima** di toccare il server: la versione online resta quella di prima.

### Vedere quale versione è online

```bash
cd /opt/ws-buzz
readlink current                 # release attiva
cat current/RELEASE.txt          # commit di backend e frontend
ls -1 releases                   # release disponibili per il rollback
```

### Tornare a una release precedente (rollback)

Sul server:

```bash
cd /opt/ws-buzz
ls -1 releases                                  # scegli la release (dalla più vecchia alla più recente)
cat releases/<nome>/RELEASE.txt                 # controlla cosa contiene
ln -sfn releases/<nome> current
docker compose up -d --force-recreate backend frontend
readlink current
```

Il rollback richiede pochi secondi, senza nessuna build. Anche questo chiude le stanze attive.

Lo script conserva le ultime 5 release, ordinate per data. Una release vecchia su cui hai fatto rollback viene eliminata ai deploy successivi, quando non è più tra le 5 più recenti.

### Aggiornare le immagini di base (Java, Nginx, Caddy)

Le immagini ricevono aggiornamenti di sicurezza. Una volta al mese, a stanze ferme:

```bash
cd /opt/ws-buzz
docker compose pull
docker compose up -d             # ricrea solo i container la cui immagine è cambiata
docker image prune -f            # elimina le immagini vecchie
```

### Log

```bash
cd /opt/ws-buzz
docker compose logs -f backend          # in tempo reale (Ctrl+C per uscire)
docker compose logs --since 1h caddy    # ultima ora
docker compose logs --tail 100 frontend
```

### Avvio, arresto e riavvio

```bash
docker compose restart backend      # riavvia il backend con la STESSA release (chiude le stanze)
docker compose stop                 # ferma tutto (il sito va offline)
docker compose up -d                # riavvia tutto
```

### Spazio su disco

```bash
df -h /                     # spazio libero
du -sh /opt/ws-buzz/releases
docker system df            # spazio usato da Docker
docker image prune -f       # immagini inutilizzate
```

**Mai** usare `docker system prune --volumes` o `docker volume prune`: cancellerebbero i certificati (vedi §15).

### Backup

L'app non ha dati da salvare: le stanze sono temporanee, e le release si ricreano dal PC con lo script. Conviene salvare solo:
- `/opt/ws-buzz/.env`, `docker-compose.yml`, `Caddyfile`;
- `C:\ws_buzz\deploy-ws-buzz.ps1` sul PC;
- facoltativo, il volume dei certificati:

```bash
cd /opt/ws-buzz
docker run --rm -v ws-buzz_caddy_data:/data -v "$PWD":/backup alpine tar czf /backup/caddy_data.tgz -C /data .
```

Se perdi i certificati, Caddy li richiede di nuovo da solo: non è grave, purché non succeda molte volte di seguito.

### Monitoraggio (facoltativo)

Configura un servizio gratuito di uptime monitoring (es. UptimeRobot, Better Stack) sull'URL `https://buzz.tuodominio.it/api/health`, con controllo della parola chiave `"status":"OK"`. Ti avvisa via email se il sito o il backend vanno giù.

---

## 12. Risoluzione dei problemi

### Sul PC (script di deploy)

| Sintomo | Causa probabile | Soluzione |
|---|---|---|
| `l'esecuzione di script è disabilitata nel sistema` | Policy di esecuzione di Windows | Lancia con `powershell -ExecutionPolicy Bypass -File ...` |
| `mvn`: `Failed to delete ...\target` | Il backend locale (`/run-be`) è in esecuzione | Fermalo e rilancia |
| `npm ci`: `EPERM` / `EBUSY` | `ng serve` è in esecuzione o un editor blocca `node_modules` | Ferma `ng serve` e rilancia |
| `Build backend fallita` | Errore di compilazione o test falliti | Leggi l'output di Maven sopra il messaggio. Il server non è stato toccato |
| `Atteso un solo jar in target` | File residui in `target` | Lo script fa `clean`: controlla che non ci siano jar copiati a mano |
| `ssh: Could not resolve hostname buzz-server` | Manca la configurazione della fase A3 | Fase A3; verifica con `ssh buzz-server` |
| `Permission denied (publickey)` | Chiave non copiata sull'utente `deploy` | Fase B4 |
| `permission denied ... docker.sock` (output del server) | `deploy` non è nel gruppo `docker` | Fase C4, poi riprova |
| `Il sito non risponde dopo 90 s` | Backend non partito, certificato non ancora pronto, DNS | Sul server: `docker compose ps` e `docker compose logs backend caddy` |
| `tar: Ignoring unknown extended header keyword` | Archivio creato con il `tar` di Windows | Avviso innocuo, si può ignorare |

### Sul server

| Sintomo | Causa probabile | Soluzione |
|---|---|---|
| `bind source path does not exist: /opt/ws-buzz/current/...` | Nessuna release caricata, oppure `current` punta a una release cancellata | Lancia lo script dal PC, oppure `ln -sfn releases/<nome> current` |
| `current` è una cartella vera invece di un collegamento (`ls -l` non mostra `->`) | È stata creata a mano | Controlla che non contenga nulla di utile, poi `rm -rf /opt/ws-buzz/current` e rilancia lo script |
| `docker-compose: command not found` o sintassi non riconosciuta | Stai usando il vecchio `docker-compose` v1 | Usa `docker compose` (con lo spazio), plugin installato nella fase C2 |
| Backend in riavvio continuo; nei log `Could not resolve placeholder 'APP_CORS_ALLOWED_ORIGINS'` | Variabile mancante | Controlla `.env` e `docker compose config` (fase F4) |
| Backend: `UnsupportedClassVersionError` | Jar compilato con un JDK più recente di 21 | Nello script, `JAVA_HOME` deve puntare al JDK 21 |
| Backend: `Unable to access jarfile` / `Permission denied` | Permessi dei file della release | `chmod -R a+rX /opt/ws-buzz/releases` e `docker compose up -d --force-recreate backend` |
| `Bind for 0.0.0.0:80 failed: port is already allocated` | Un altro server web occupa la porta 80/443 | Fase B10 |
| Caddy: `challenge failed`, `timeout during connect`, `NXDOMAIN` | DNS non ancora propagato, porte 80/443 chiuse (anche nel firewall del provider), `AAAA` sbagliato, proxy Cloudflare attivo | Fasi D e B8. Poi `docker compose restart caddy` |
| Caddy: `too many certificates` / `rateLimited` | Troppi tentativi falliti | Aspetta (da 1 ora a qualche giorno, secondo il limite). Per le prove usa lo staging: aggiungi `acme_ca https://acme-staging-v02.api.letsencrypt.org/directory` nel blocco globale del `Caddyfile`, e toglilo quando funziona |
| Pagina **502** generata da Caddy | Il container `frontend` è fermo | `docker compose ps`, `docker compose logs frontend` |
| Pagina **502** generata da Nginx (scritta "nginx") su `/api` o `/ws` | Backend fermo o ancora in avvio (servono 10–20 s) | `docker compose logs backend` |
| Frontend: `nginx: [emerg]` nei log | Errore in `nginx.conf` | Correggi `C:\ws_buzz\ws_buzz\nginx.conf` e rilancia lo script, oppure fai rollback |
| Il sito si carica ma "Stanza non trovata" dopo un deploy | Normale: il riavvio del backend ha chiuso le stanze | Crea una stanza nuova |
| **404** aprendo `/room/XXXX/play` o con F5 | `nginx.conf` non montato o sbagliato | `docker compose exec frontend cat /etc/nginx/conf.d/default.conf` deve mostrare il file del repo |
| WebSocket con errore **403** | L'origine non è ammessa | `/api/health` → `checks.cors`; correggi `DOMAIN` in `.env`, poi `docker compose up -d` |
| WebSocket che cade ogni 60 s circa | Timeout del proxy | Controlla `proxy_read_timeout` in `nginx.conf`. Se c'è un altro proxy davanti (§14), aumenta anche il suo timeout |
| Dopo un deploy si vede ancora la versione vecchia | Cache del browser, oppure container non ricreati | Ricarica forzata (Ctrl+F5); controlla `buildTime` su `/api/health` e `readlink current` |
| `/api/health` → `checks.https` falso | `X-Forwarded-Proto` non arriva al backend | Verifica `nginx.conf`. Se Docker usa reti fuori da 10.x/172.16–31.x/192.168.x, vedi §13 |
| Il disco si riempie | Immagini vecchie, log | §11 "Spazio su disco" |
| **Non ho un dominio** | Senza dominio Let's Encrypt non rilascia il certificato | Solo per prove: nel `Caddyfile` sostituisci `{$DOMAIN}` con `:80`, e nel compose usa `APP_CORS_ALLOWED_ORIGINS: http://IP_DEL_SERVER`. Il sito sarà in HTTP, non adatto all'uso reale |

Comandi utili per indagare:

```bash
cd /opt/ws-buzz
docker compose ps -a                          # stato e codici di uscita
docker compose logs --tail 200 <servizio>
ls -l current && cat current/RELEASE.txt      # release attiva
docker inspect --format '{{json .State.Health}}' ws-buzz-frontend-1   # dettaglio healthcheck
docker compose exec frontend wget -qO- http://backend:8080/api/health # backend visto dalla rete interna
```

---

## 13. Riferimento: configurazione di backend e frontend

### Backend: variabili d'ambiente

| Variabile | Valore | Note |
|---|---|---|
| `APP_CORS_ALLOWED_ORIGINS` | `https://buzz.tuodominio.it` | **Obbligatoria**: senza, il backend non parte. Nel compose è ricavata da `DOMAIN`. Più origini vanno separate da virgola, **senza spazi**, e devono coincidere con l'URL del sito, schema compreso. |
| `JAVA_TOOL_OPTIONS` | `-XX:MaxRAMPercentage=75` | La JVM usa al massimo il 75% della memoria del container. |
| `TZ` | `Europe/Rome` | Orari dei log. |

- **Non impostare** `SERVER_ADDRESS=127.0.0.1`: dentro il container il backend deve restare in ascolto su tutte le interfacce, altrimenti Nginx non lo raggiunge. La protezione si ottiene non pubblicando la porta 8080.
- **Non attivare** il profilo `dev` (`SPRING_PROFILES_ACTIVE`).

### Backend: configurazione già presente nel codice

- `server.forward-headers-strategy=native`: il backend legge gli header `X-Forwarded-*` solo se la richiesta arriva da un IP privato (10.x, 172.16–31.x, 192.168.x, localhost). Le reti Docker di default usano questi intervalli. Se hai configurato Docker con pool di indirizzi diversi (`default-address-pools` in `daemon.json`), aggiungi nel compose la variabile `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` con una regex degli IP della rete Docker.
- Heartbeat STOMP ogni 10 s: il WebSocket resta aperto attraverso i proxy.
- `GET /api/health`: diagnostica dell'installazione (fase H), usata anche dall'healthcheck del container `frontend` e dalla verifica finale dello script.
- Il jar contiene versione e data di build (`version`, `buildTime` in `/api/health`).
- Log su stdout, consultabili con `docker compose logs backend`.

### Backend: limiti da conoscere

- **Stato in memoria**: ogni deploy, rollback o riavvio del backend chiude tutte le stanze attive.
- **Una sola istanza**: il broker WebSocket è in memoria, con due istanze i giocatori di una stessa stanza non si vedrebbero.
- Le stanze abbandonate senza "esci" (es. l'host chiude il browser) restano in memoria fino al riavvio successivo. Con l'uso normale non è un problema, e `activeRooms` su `/api/health` permette di tenerle d'occhio.

### Frontend: cosa contiene la release

- `browser/` è l'output di `ng build` (configurazione `production`): l'app usa URL relativi (`/api`, `/ws`) e costruisce `wss://` da sola in HTTPS. Non c'è nessun URL da impostare per l'ambiente.
- `nginx.conf` viene dal repo `ws_buzz` e definisce:
  - inoltro di `/api` e `/ws` al container `backend` (con gli header per il WebSocket e timeout di 120 s);
  - risoluzione del nome `backend` a ogni richiesta (`resolver 127.0.0.11`), così un backend ricreato con un IP nuovo non causa errori 502;
  - `try_files ... /index.html` per le rotte Angular (link del QR code, refresh);
  - `index.html` senza cache e JS/CSS con cache lunga (i loro nomi cambiano a ogni build).

### File Docker presenti nei repository

`ws_buzz_be/Dockerfile`, `ws_buzz/Dockerfile` e i relativi `.dockerignore` servono alla guida alternativa [DEPLOY_DOCKER_BUILD_SERVER.md](DEPLOY_DOCKER_BUILD_SERVER.md), dove le immagini si costruiscono **compilando sul server**. Con il deploy di questa guida **non vengono usati**: non cancellarli se vuoi mantenere entrambe le modalità.

---

## 14. Variante: sul server c'è già un reverse proxy

Se sul server gira già Nginx, Apache o Traefik per altri siti, non usare Caddy.

1. Nel `docker-compose.yml` elimina il servizio `caddy` e la sezione `volumes` in fondo, e pubblica il frontend **solo su localhost**:

   ```yaml
     frontend:
       ports:
         - "127.0.0.1:8081:80"
   ```

2. Configura il proxy esistente in modo che inoltri `buzz.tuodominio.it` a `http://127.0.0.1:8081` con:
   - gli header `Upgrade` e `Connection "upgrade"` (necessari per `/ws`);
   - `Host`, `X-Forwarded-Proto` e `X-Forwarded-For`;
   - un timeout di lettura ≥ 60 s (consigliato 120 s);
   - il certificato HTTPS (es. con certbot).

3. `nginx.conf` del frontend e script di deploy restano invariati. Nella fase B10 non disattivare il server web esistente.

---

## 15. Cose da NON fare

- Non pubblicare la porta 8080 del backend (nessun `ports:` sul servizio `backend`): Docker scavalca `ufw`.
- Non avviare più di un'istanza del backend.
- Non impostare `SPRING_PROFILES_ACTIVE=dev` né `SERVER_ADDRESS=127.0.0.1` nel container.
- Non mettere `https://`, `/` finali o spazi in `DOMAIN` nel file `.env`.
- Non creare a mano la cartella `current` e non avviare il compose prima della prima release.
- Non modificare i file dentro `releases/` sul server: correggi sul PC e fai un nuovo deploy, così ogni release resta riproducibile.
- Non compilare il backend con un JDK diverso da 21.
- Non usare `docker compose restart` per cambiare release: serve `up -d --force-recreate` (lo fa lo script).
- Non cancellare il volume `caddy_data` (niente `docker compose down -v`, `docker volume prune`, `docker system prune --volumes`): ogni nuovo certificato conta nei limiti di Let's Encrypt.
- Non avviare Caddy prima che il DNS punti al server (fase D).
- Non abilitare `ufw` senza aver prima consentito `OpenSSH`.
- Non disabilitare l'accesso SSH con password senza aver verificato che l'accesso con chiave funziona.
- Non fare deploy mentre qualcuno sta giocando.
- Non fare commit di `.env` o dello script di deploy con dati del server nei repository (il backend è pubblico).
