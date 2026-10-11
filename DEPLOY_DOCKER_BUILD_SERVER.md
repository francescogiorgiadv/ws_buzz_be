# Deploy di ws-buzz con Docker su Ubuntu — build sul server

Guida completa per installare backend (`ws_buzz_be`) e frontend (`ws_buzz`) in container Docker su un server Ubuntu: dalla macchina appena creata fino all'app online in HTTPS, più aggiornamenti, rollback e risoluzione dei problemi.

**Come funziona il deploy:** il server scarica il codice da GitHub e **compila backend e frontend dentro Docker** (Dockerfile presenti nei repository).

> Esiste anche la guida alternativa [DEPLOY_DOCKER_BUILD_PC.md](DEPLOY_DOCKER_BUILD_PC.md), in cui compili sul tuo PC e carichi sul server solo i file già pronti (niente git né build sul server, rollback istantaneo).

> I Dockerfile, la configurazione Nginx, il `docker-compose.yml` e il `Caddyfile` di questa guida **non sono ancora stati provati** (Docker non era disponibile sulla macchina di sviluppo). Al primo deploy segui le verifiche della fase H, e in caso di errori consulta il §12.

Nei comandi, sostituisci sempre:

| Segnaposto | Cosa mettere |
|---|---|
| `buzz.tuodominio.it` | il dominio (o sottodominio) del sito |
| `IP_DEL_SERVER` | l'IP pubblico del server |
| `tua@email.it` | la tua email (Let's Encrypt la usa per gli avvisi di scadenza dei certificati) |
| `deploy` | il nome dell'utente Linux che userai sul server (puoi tenere questo) |

---

## Indice

1. [Architettura](#1-architettura)
2. [Cosa serve prima di iniziare](#2-cosa-serve-prima-di-iniziare)
3. [Fase A — Preparare i repository (sul PC)](#3-fase-a--preparare-i-repository-sul-pc)
4. [Fase B — Preparare il server Ubuntu](#4-fase-b--preparare-il-server-ubuntu)
5. [Fase C — Installare Docker](#5-fase-c--installare-docker)
6. [Fase D — Configurare il DNS](#6-fase-d--configurare-il-dns)
7. [Fase E — Scaricare il codice sul server](#7-fase-e--scaricare-il-codice-sul-server)
8. [Fase F — File di configurazione del deploy](#8-fase-f--file-di-configurazione-del-deploy)
9. [Fase G — Build e primo avvio](#9-fase-g--build-e-primo-avvio)
10. [Fase H — Verifiche](#10-fase-h--verifiche)
11. [Gestione quotidiana: aggiornamenti, rollback, log, pulizia, backup](#11-gestione-quotidiana)
12. [Risoluzione dei problemi](#12-risoluzione-dei-problemi)
13. [Riferimento: configurazione del backend](#13-riferimento-configurazione-del-backend)
14. [Variante: sul server c'è già un reverse proxy](#14-variante-sul-server-cè-già-un-reverse-proxy)
15. [Cose da NON fare](#15-cose-da-non-fare)

---

## 1. Architettura

```
Internet ──HTTPS──▶ caddy (443/80) ──HTTP──▶ frontend (Nginx :80) ──┬── file statici Angular
                                                                     ├── /api/* ──▶ backend :8080
                                                                     └── /ws    ──▶ backend :8080 (WebSocket)
```

- **3 container**:
  - `caddy` gestisce l'HTTPS e ottiene e rinnova da solo il certificato Let's Encrypt;
  - `frontend` è un Nginx che serve l'app Angular e inoltra `/api` e `/ws` al backend;
  - `backend` è l'applicazione Spring Boot.
- **Un solo dominio pubblico.** Il frontend chiama il backend con URL relativi (`/api`, `/ws`), quindi:
  - l'immagine del frontend è identica in ogni ambiente (nessun URL da impostare al momento della build);
  - per il browser è tutto la stessa origine, quindi non ci sono richieste CORS.
- **Solo `caddy` espone porte sull'host** (80 e 443). `frontend` e `backend` sono raggiungibili solo dalla rete interna di Docker.
- **Nessun database**: lo stato delle stanze è in memoria nel backend. Riavviare il backend chiude tutte le stanze attive.

---

## 2. Cosa serve prima di iniziare

| Cosa | Dettagli |
|---|---|
| **Server (VPS)** | Ubuntu Server **24.04 LTS** (va bene anche 22.04). Architettura x86_64 oppure ARM64. |
| **RAM** | Consigliati **2 GB**. Con 1 GB si può, ma serve lo swap (fase B6): le build di Maven e Angular consumano molta memoria. |
| **Disco** | Almeno **20 GB** (immagini Docker, cache di build, log). |
| **Dominio** | Un dominio o sottodominio di cui puoi gestire il DNS (es. `buzz.tuodominio.it`). Senza dominio non si ottiene il certificato HTTPS (vedi il §12, "Non ho un dominio"). |
| **Accesso SSH** | IP del server e credenziali iniziali date dal provider (utente `root` con password o chiave). |
| **GitHub** | Accesso ai due repository: `ws_buzz_be` (pubblico) e `ws_buzz` (**privato**: serve una deploy key, vedi la fase E). |
| **Sul PC Windows** | Git, il client `ssh` (incluso in Windows 10/11) e i progetti aggiornati in `C:\ws_buzz`. |

---

## 3. Fase A — Preparare i repository (sul PC)

Il server scarica il codice da GitHub, quindi tutto quello che serve alla build deve essere **committato e pushato**.

I file Docker di A1 e A2 sono **già presenti nei repository**: il loro contenuto è riportato qui solo come riferimento. Se li modifichi, tieni allineata anche questa guida.

### A1. File Docker del backend (`C:\ws_buzz\ws_buzz_be`)

**`ws_buzz_be/Dockerfile`**

```dockerfile
# syntax=docker/dockerfile:1

# --- build ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
# La cache di ~/.m2 sopravvive tra una build e l'altra: le dipendenze si scaricano una volta sola.
# I test si eseguono prima del push (vedi DEPLOY_DOCKER_BUILD_SERVER.md, fase A3).
RUN --mount=type=cache,target=/root/.m2 mvn -q -B package -DskipTests

# --- runtime ---
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 1001 app
WORKDIR /app
COPY --from=build /src/target/*.jar /app/app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

`-DskipTests` velocizza la build dell'immagine. I test vanno eseguiti **prima del push**, in locale (`/clean-install` o `mvnw test`).

**`ws_buzz_be/.dockerignore`**

```
.git/
target/
bin/
.idea/
.vscode/
.claude/
*.iml
```

### A2. File Docker del frontend (`C:\ws_buzz\ws_buzz`)

**`ws_buzz/Dockerfile`**

```dockerfile
# syntax=docker/dockerfile:1

# --- build ---
FROM node:24-alpine AS build
WORKDIR /src
ENV NG_CLI_ANALYTICS=false
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY . .
RUN npx ng build

# --- runtime ---
FROM nginx:stable-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist/ws-buzz/browser /usr/share/nginx/html
EXPOSE 80
```

**`ws_buzz/.dockerignore`**

```
.git/
node_modules/
dist/
.angular/
.vscode/
.idea/
.claude/
```

**`ws_buzz/nginx.conf`**

```nginx
# Configurazione Nginx del container frontend, usata da entrambe le guide di
# deploy in ws_buzz_be (DEPLOY_DOCKER_BUILD_SERVER.md e DEPLOY_DOCKER_BUILD_PC.md):
# serve l'app Angular e inoltra /api e /ws al container "backend" sulla rete
# interna di Docker.

# Protocollo originale del client: se davanti c'e' Caddy arriva da lui
# (https), altrimenti si usa quello della connessione corrente.
map $http_x_forwarded_proto $fwd_proto {
    default $http_x_forwarded_proto;
    ''      $scheme;
}

server {
    listen 80;
    server_name _;
    root /usr/share/nginx/html;
    server_tokens off;

    # DNS interno di Docker: il nome "backend" viene risolto a ogni richiesta
    # (cache 10s). Senza, Nginx memorizza l'IP all'avvio e, se il container
    # backend viene ricreato con un IP nuovo, risponde 502 finche' non si
    # riavvia anche il frontend.
    resolver 127.0.0.11 valid=10s ipv6=off;
    set $backend http://backend:8080;

    # --- API REST ---
    location ^~ /api/ {
        proxy_pass $backend;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $fwd_proto;
    }

    # --- WebSocket STOMP ---
    location ^~ /ws {
        proxy_pass $backend;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $fwd_proto;
        # Il backend manda un heartbeat ogni 10s: 120s danno ampio margine.
        proxy_read_timeout 120s;
        proxy_send_timeout 120s;
    }

    # --- index.html: sempre riscaricato, cosi' dopo un deploy arriva la versione nuova ---
    location = /index.html {
        add_header Cache-Control "no-cache";
    }

    # --- JS/CSS con hash nel nome: cache lunga ---
    location ~* \.(?:js|css|woff2?|ico|svg|png)$ {
        add_header Cache-Control "public, max-age=31536000, immutable";
        try_files $uri =404;
    }

    # --- Rotte Angular (/room/XXXX/play dal QR code, refresh pagina) ---
    location / {
        try_files $uri $uri/ /index.html;
    }
}
```

`backend` è il nome del servizio nel `docker-compose.yml`: se lo rinomini, aggiornalo anche qui.

### A3. Verificare in locale prima del push

1. Backend: `/clean-install`. Deve finire con `BUILD SUCCESS`, test compresi.
2. Frontend: `npx ng build` in `C:\ws_buzz\ws_buzz`, senza errori.
3. Facoltativo: se hai Docker Desktop, prova `docker build .` in entrambe le cartelle.

### A4. Commit e push

Vanno pushate **tutte** le modifiche fatte per il deploy (CORS da variabile d'ambiente, heartbeat, `/api/health`, URL relativi, proxy, toast errori) e i file Docker:

```powershell
cd C:\ws_buzz\ws_buzz_be
git status
git add -A
git commit -m "configurazione per deploy Docker"
git push origin master

cd C:\ws_buzz\ws_buzz
git status
git add -A
git commit -m "configurazione per deploy Docker"
git push origin master
```

Prima di `git add -A`, controlla in `git status` che non ci siano file da non pubblicare. Il repo `ws_buzz_be` è **pubblico**.

---

## 4. Fase B — Preparare il server Ubuntu

Tutti i comandi di questa fase e delle successive si eseguono **sul server**, via SSH, salvo dove indicato "sul PC".

### B1. Primo accesso

Sul PC (PowerShell):

```powershell
ssh root@IP_DEL_SERVER
```

Alla prima connessione rispondi `yes` alla domanda sull'impronta (fingerprint) del server.

### B2. Aggiornare il sistema

```bash
apt update && apt full-upgrade -y
apt install -y git curl ca-certificates dnsutils ufw
reboot
```

Dopo il riavvio aspetta un minuto e ricollegati con `ssh root@IP_DEL_SERVER`.

### B3. Creare una chiave SSH sul PC (se non ce l'hai già)

Sul PC (PowerShell):

```powershell
Test-Path $env:USERPROFILE\.ssh\id_ed25519.pub
# se risponde False:
ssh-keygen -t ed25519 -C "pc-windows"
```

Premi Invio per il percorso di default. Imposta una passphrase, consigliata.

### B4. Creare l'utente di lavoro

Non lavorare come `root`. Sul server:

```bash
adduser deploy                 # chiede una password: sceglila robusta, servirà per sudo
usermod -aG sudo deploy
```

Copia la tua chiave pubblica sul nuovo utente. Sul PC (PowerShell):

```powershell
type $env:USERPROFILE\.ssh\id_ed25519.pub | ssh root@IP_DEL_SERVER "mkdir -p /home/deploy/.ssh && cat >> /home/deploy/.ssh/authorized_keys && chown -R deploy:deploy /home/deploy/.ssh && chmod 700 /home/deploy/.ssh && chmod 600 /home/deploy/.ssh/authorized_keys"
```

**Prova subito** ad accedere con il nuovo utente, da una **seconda** finestra PowerShell, lasciando aperta quella di root:

```powershell
ssh deploy@IP_DEL_SERVER
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
- **Prima di chiudere la sessione aperta**, prova un nuovo accesso da un'altra finestra (`ssh deploy@IP_DEL_SERVER`). Se non entri, hai ancora la vecchia sessione per correggere.

### B6. Swap (indispensabile con 1 GB di RAM, utile con 2 GB)

```bash
swapon --show          # se mostra già uno swap di almeno 2G, salta questo passo
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
free -h                # la riga "Swap" deve mostrare 2.0Gi
```

Senza swap, una build che finisce la memoria viene interrotta con l'errore `exit code 137` / `Killed`.

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

```bash
sudo usermod -aG docker deploy
exit
```

Ricollegati (`ssh deploy@IP_DEL_SERVER`) perché il gruppo abbia effetto, poi prova:

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

## 7. Fase E — Scaricare il codice sul server

### E1. Cartella del deploy

```bash
sudo mkdir -p /opt/ws-buzz
sudo chown deploy:deploy /opt/ws-buzz
cd /opt/ws-buzz
```

### E2. Chiave di accesso al repository privato del frontend (deploy key)

Il repo `ws_buzz` è privato, quindi il server ha bisogno di una chiave dedicata con **sola lettura**.

```bash
ssh-keygen -t ed25519 -f ~/.ssh/ws_buzz_deploy -N "" -C "deploy ws_buzz su $(hostname)"
cat ~/.ssh/ws_buzz_deploy.pub
```

Copia la riga stampata, poi su GitHub:
1. vai al repo **ws_buzz** → **Settings** → **Deploy keys** → **Add deploy key**;
2. Title: `server produzione`; Key: incolla la riga;
3. lascia **deselezionato** "Allow write access" → **Add key**.

Sul server, configura SSH per usare quella chiave solo per questo repo:

```bash
cat >> ~/.ssh/config <<'EOF'
Host github-ws_buzz
    HostName github.com
    User git
    IdentityFile ~/.ssh/ws_buzz_deploy
    IdentitiesOnly yes
EOF
chmod 600 ~/.ssh/config

ssh-keyscan github.com >> ~/.ssh/known_hosts
ssh -T git@github-ws_buzz
# atteso: "Hi francescogiorgiadv/ws_buzz! You've successfully authenticated, but GitHub does not provide shell access."
```

Una deploy key vale per **un solo repository**. Se in futuro rendi privato anche `ws_buzz_be`, ripeti questo passo con una seconda chiave (`ws_buzz_be_deploy`) e un secondo alias (`github-ws_buzz_be`).

### E3. Clonare i due repository

I nomi delle cartelle devono essere **esattamente** questi, perché il compose li usa:

```bash
cd /opt/ws-buzz
git clone https://github.com/francescogiorgiadv/ws_buzz_be.git ws_buzz_be
git clone git@github-ws_buzz:francescogiorgiadv/ws_buzz.git ws_buzz
ls ws_buzz_be/Dockerfile ws_buzz/Dockerfile ws_buzz/nginx.conf    # devono esistere tutti e tre
```

Se un file manca, la fase A4 (push) non è stata completata.

---

## 8. Fase F — File di configurazione del deploy

Questi tre file stanno **solo sul server**, in `/opt/ws-buzz`, e non vanno nei repository.

```
/opt/ws-buzz/
├── .env                 # dominio ed email (unico punto da modificare)
├── docker-compose.yml
├── Caddyfile
├── ws_buzz/             # frontend (git)
└── ws_buzz_be/          # backend (git)
```

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
    build: ./ws_buzz_be
    image: ws-buzz-backend:latest
    restart: unless-stopped
    environment:
      # Ricavata dal dominio: e' l'unica origine da cui il browser chiama il backend.
      APP_CORS_ALLOWED_ORIGINS: https://${DOMAIN:?imposta DOMAIN nel file .env}
      JAVA_TOOL_OPTIONS: -XX:MaxRAMPercentage=75
      TZ: Europe/Rome
    mem_limit: 512m
    # nessuna "ports": raggiungibile solo dalla rete interna di Docker

  frontend:
    build: ./ws_buzz
    image: ws-buzz-frontend:latest
    restart: unless-stopped
    depends_on:
      - backend
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
- 512 MB per il backend sono abbondanti per qualche decina di giocatori. Con `MaxRAMPercentage=75` l'heap Java arriva al massimo a circa 384 MB.
- **Non** aggiungere `replicas` né scalare il backend: deve esistere una sola istanza.

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

## 9. Fase G — Build e primo avvio

### G1. Build delle immagini

```bash
cd /opt/ws-buzz
docker compose build
```

La prima volta richiede **5–15 minuti**, perché scarica le immagini base, le dipendenze Maven e npm e compila. Le volte successive è molto più veloce grazie alla cache. Deve finire senza errori, con le immagini `ws-buzz-backend` e `ws-buzz-frontend` costruite.

### G2. Avvio

```bash
docker compose up -d
docker compose ps
```

Tutti e tre i servizi devono risultare `running`. `frontend` diventa `healthy` entro circa 1 minuto.

### G3. Log del primo avvio

```bash
docker compose logs backend | tail -n 30
# deve comparire:  No active profile set ...
#                  Started WsBuzzBackendApplication in ... seconds

docker compose logs caddy | grep -iE 'certificate obtained|error'
# deve comparire:  certificate obtained successfully   (per buzz.tuodominio.it)
```

Il certificato si ottiene di solito in 10–60 secondi. Se compaiono errori, vedi il §12 prima di riprovare, per non incorrere nel limite di frequenza di Let's Encrypt.

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
   | `version` / `buildTime` | quelli dell'ultima build | Il container usa un'immagine vecchia: `docker compose up -d --build` |
   | `webSocketSessions` | `N current WS(N)…` con N > 0 mentre qualcuno è in una stanza | Il WebSocket non arriva al backend: controlla la rotta `/ws` in `nginx.conf` |

4. **WebSocket**: crea una stanza. In DevTools (F12) → Network → filtro **WS** deve esserci `wss://buzz.tuodominio.it/ws` con stato `101`.
5. **QR code**: dal telefono, in **rete mobile** (non in Wi-Fi, così provi davvero dall'esterno), scansiona il QR code. La pagina `/room/XXXX/play` si deve aprire, non un 404.
6. **Refresh**: ricarica (F5) la pagina host. Deve riaprirsi senza 404.
7. **Partita di prova**: con 2–3 dispositivi prova buzz, reset, lock, cambio modalità e punteggio.
8. **Connessione inattiva**: lascia la stanza aperta più di 2 minuti senza fare nulla. Il WebSocket non deve cadere: in DevTools la connessione resta aperta e continuano ad arrivare frame di heartbeat.
9. **Errori** (facoltativo): quando il server rifiuta un'azione, l'app mostra un toast rosso in basso con il messaggio. Dall'interfaccia è difficile provocarlo apposta. Se durante le prove compare, il canale degli errori funziona. Se un'azione non ha effetto e il toast non compare, controlla `docker compose logs backend` (righe `WARN ... Operazione rifiutata` o `Stanza non trovata`).
10. **Riavvio del server**: `sudo reboot`. Dopo un paio di minuti, con `ssh deploy@IP_DEL_SERVER` e poi `cd /opt/ws-buzz && docker compose ps`, tutti i servizi devono essere di nuovo `running` e il sito deve funzionare.
11. **Porte chiuse**: dal PC (PowerShell), `Test-NetConnection IP_DEL_SERVER -Port 8080` deve dare `TcpTestSucceeded : False`.

---

## 11. Gestione quotidiana

### Aggiornare l'app a una nuova versione

**Ogni aggiornamento riavvia il backend e chiude tutte le stanze attive**: fallo quando nessuno sta giocando.

Prima fai commit e push dal PC, poi sul server:

```bash
cd /opt/ws-buzz
git -C ws_buzz_be pull --ff-only
git -C ws_buzz pull --ff-only
docker compose build            # costruisce le nuove immagini mentre la vecchia versione è ancora online
docker compose up -d            # sostituisce solo i container cambiati (pochi secondi di interruzione)
docker image prune -f           # elimina le immagini vecchie rimaste senza nome
```

Verifica su `https://buzz.tuodominio.it/api/health` che `buildTime` sia quello appena prodotto.

Per comodità puoi salvare questi comandi in uno script:

```bash
cat > /opt/ws-buzz/update.sh <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
cd /opt/ws-buzz
git -C ws_buzz_be pull --ff-only
git -C ws_buzz pull --ff-only
docker compose build
docker compose up -d
docker image prune -f
docker compose ps
EOF
chmod +x /opt/ws-buzz/update.sh
# uso: /opt/ws-buzz/update.sh
```

### Tornare alla versione precedente (rollback)

```bash
cd /opt/ws-buzz
git -C ws_buzz_be log --oneline -n 5         # individua il commit buono
git -C ws_buzz_be checkout <commit>          # idem per ws_buzz, se serve
docker compose up -d --build
```

Quando il problema è risolto e pushato, torna al ramo principale:

```bash
git -C ws_buzz_be checkout master
git -C ws_buzz checkout master
/opt/ws-buzz/update.sh
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
docker compose restart backend      # riavvia solo il backend (chiude le stanze)
docker compose stop                 # ferma tutto (il sito va offline)
docker compose up -d                # riavvia tutto
```

### Spazio su disco

```bash
df -h /                     # spazio libero
docker system df            # spazio usato da Docker
docker builder prune -f     # svuota la cache di build (la prossima build sarà più lenta)
docker image prune -f       # immagini inutilizzate
```

**Mai** usare `docker system prune --volumes` o `docker volume prune`: cancellerebbero i certificati (vedi §15).

### Backup

L'app non ha dati da salvare (le stanze sono temporanee). Conviene salvare solo la configurazione:
- `/opt/ws-buzz/.env`, `docker-compose.yml`, `Caddyfile`, `update.sh`;
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

| Sintomo | Causa probabile | Soluzione |
|---|---|---|
| `permission denied ... docker.sock` | L'utente non è ancora nel gruppo `docker` | Esci e ricollegati via SSH (fase C4) |
| `docker-compose: command not found` o sintassi non riconosciuta | Stai usando il vecchio `docker-compose` v1 | Usa `docker compose` (con lo spazio), plugin installato nella fase C2 |
| La build si interrompe con `Killed` / `exit code 137` | Memoria finita | Aggiungi lo swap (fase B6) |
| `git clone` del frontend: `Permission denied (publickey)` / `Repository not found` | Deploy key mancante o alias sbagliato | Ripeti la fase E2; `ssh -T git@github-ws_buzz` deve rispondere "successfully authenticated" |
| `COPY failed: ... nginx.conf not found` o `Dockerfile not found` | I file della fase A non sono stati pushati | Fase A4, poi `git pull` sul server |
| `npm ci`: `Cannot find module @rollup/rollup-linux-...` | `package-lock.json` incompleto per Linux | Sul PC: cancella `node_modules` e `package-lock.json`, `npm install`, commit e push del lockfile |
| Backend in riavvio continuo; nei log `Could not resolve placeholder 'APP_CORS_ALLOWED_ORIGINS'` | Variabile mancante | Controlla `.env` e `docker compose config` (fase F4) |
| `Bind for 0.0.0.0:80 failed: port is already allocated` | Un altro server web occupa la porta 80/443 | Fase B10 |
| Caddy: `challenge failed`, `timeout during connect`, `NXDOMAIN` | DNS non ancora propagato, porte 80/443 chiuse (anche nel firewall del provider), `AAAA` sbagliato, proxy Cloudflare attivo | Fasi D e B8. Poi `docker compose restart caddy` |
| Caddy: `too many certificates` / `rateLimited` | Troppi tentativi falliti | Aspetta (da 1 ora a qualche giorno, secondo il limite). Per le prove usa lo staging: aggiungi `acme_ca https://acme-staging-v02.api.letsencrypt.org/directory` nel blocco globale del `Caddyfile`, e toglilo quando funziona |
| Pagina **502** generata da Caddy | Il container `frontend` è fermo | `docker compose ps`, `docker compose logs frontend` |
| Pagina **502** generata da Nginx (scritta "nginx") su `/api` o `/ws` | Backend fermo o ancora in avvio (servono 10–20 s) | `docker compose logs backend` |
| Il sito si carica ma "Stanza non trovata" dopo un aggiornamento | Normale: il riavvio del backend ha chiuso le stanze | Crea una stanza nuova |
| **404** aprendo `/room/XXXX/play` o con F5 | `nginx.conf` non incluso nell'immagine | Verifica `ws_buzz/nginx.conf` e `docker compose up -d --build frontend` |
| WebSocket con errore **403** | L'origine non è ammessa | `/api/health` → `checks.cors`; correggi `DOMAIN` in `.env`, poi `docker compose up -d` |
| WebSocket che cade ogni 60 s circa | Timeout del proxy | Controlla `proxy_read_timeout` in `nginx.conf`. Se c'è un altro proxy davanti (§14), aumenta anche il suo timeout |
| Dopo un aggiornamento si vede ancora la versione vecchia | Cache del browser | Ricarica forzata (Ctrl+F5); verifica `buildTime` su `/api/health` |
| `/api/health` → `checks.https` falso | `X-Forwarded-Proto` non arriva al backend | Verifica `nginx.conf`. Se Docker usa reti fuori da 10.x/172.16–31.x/192.168.x, vedi §13 |
| Il disco si riempie | Cache di build e immagini vecchie | §11 "Spazio su disco" |
| **Non ho un dominio** | Senza dominio Let's Encrypt non rilascia il certificato | Solo per prove: nel `Caddyfile` sostituisci `{$DOMAIN}` con `:80`, e in `.env`/compose usa `APP_CORS_ALLOWED_ORIGINS: http://IP_DEL_SERVER`. Il sito sarà in HTTP, non adatto all'uso reale |

Comandi utili per indagare:

```bash
docker compose ps -a                          # stato e codici di uscita
docker compose logs --tail 200 <servizio>
docker inspect --format '{{json .State.Health}}' ws-buzz-frontend-1   # dettaglio healthcheck
docker compose exec frontend wget -qO- http://backend:8080/api/health # backend visto dalla rete interna
```

---

## 13. Riferimento: configurazione del backend

### Variabili d'ambiente

| Variabile | Valore | Note |
|---|---|---|
| `APP_CORS_ALLOWED_ORIGINS` | `https://buzz.tuodominio.it` | **Obbligatoria**: senza, il backend non parte. Nel compose è ricavata da `DOMAIN`. Più origini vanno separate da virgola, **senza spazi**, e devono coincidere con l'URL del sito, schema compreso. |
| `JAVA_TOOL_OPTIONS` | `-XX:MaxRAMPercentage=75` | La JVM usa al massimo il 75% della memoria del container. |
| `TZ` | `Europe/Rome` | Orari dei log. |

- **Non impostare** `SERVER_ADDRESS=127.0.0.1`: dentro il container il backend deve restare in ascolto su tutte le interfacce, altrimenti Nginx non lo raggiunge. La protezione si ottiene non pubblicando la porta 8080.
- **Non attivare** il profilo `dev` (`SPRING_PROFILES_ACTIVE`).

### Configurazione già presente nel codice

- `server.forward-headers-strategy=native`: il backend legge gli header `X-Forwarded-*` solo se la richiesta arriva da un IP privato (10.x, 172.16–31.x, 192.168.x, localhost). Le reti Docker di default usano questi intervalli. Se hai configurato Docker con pool di indirizzi diversi (`default-address-pools` in `daemon.json`), aggiungi la variabile `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` con una regex degli IP della rete Docker.
- Heartbeat STOMP ogni 10 s: il WebSocket resta aperto attraverso i proxy.
- `GET /api/health`: diagnostica dell'installazione (fase H), usata anche dall'healthcheck del container `frontend`.
- Log su stdout, consultabili con `docker compose logs backend`.

### Limiti da conoscere

- **Stato in memoria**: ogni riavvio, ricreazione o aggiornamento del backend chiude tutte le stanze attive.
- **Una sola istanza**: il broker WebSocket è in memoria, con due istanze i giocatori di una stessa stanza non si vedrebbero.
- Le stanze abbandonate senza "esci" (es. l'host chiude il browser) restano in memoria fino al riavvio successivo. Con l'uso normale non è un problema, e `activeRooms` su `/api/health` permette di tenerle d'occhio.

---

## 14. Variante: sul server c'è già un reverse proxy

Se sul server gira già Nginx, Apache o Traefik per altri siti, non usare Caddy.

1. Nel `docker-compose.yml` elimina il servizio `caddy` e la sezione `volumes`, e pubblica il frontend **solo su localhost**:

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

3. Il `nginx.conf` del frontend resta invariato.

---

## 15. Cose da NON fare

- Non pubblicare la porta 8080 del backend (nessun `ports:` sul servizio `backend`): Docker scavalca `ufw`.
- Non avviare più di un'istanza del backend.
- Non impostare `SPRING_PROFILES_ACTIVE=dev` né `SERVER_ADDRESS=127.0.0.1` nel container.
- Non mettere `https://`, `/` finali o spazi in `DOMAIN` nel file `.env`.
- Non cancellare il volume `caddy_data` (niente `docker compose down -v`, `docker volume prune`, `docker system prune --volumes`): ogni nuovo certificato conta nei limiti di Let's Encrypt.
- Non avviare Caddy prima che il DNS punti al server (fase D).
- Non abilitare `ufw` senza aver prima consentito `OpenSSH`.
- Non disabilitare l'accesso SSH con password senza aver verificato che l'accesso con chiave funziona.
- Non aggiornare l'app mentre qualcuno sta giocando.
- Non fare commit di `.env` o di altri file del server nei repository (il backend è pubblico).
