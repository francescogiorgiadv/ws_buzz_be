---
description: Compila backend (ws_buzz_be) e frontend (ws_buzz) sul PC e li rilascia sul server Docker remoto, in un nuovo terminale PowerShell
argument-hint: [-Domain <dominio>] [-Server <host ssh>] [-SkipTests]
allowed-tools: Bash, PowerShell
---

# Comando /deploy

Esegue lo script `C:\ws_buzz\ws_buzz_be\scripts\deploy-ws-buzz.ps1`, che:
1. controlla la connessione SSH al server e la presenza di `/opt/ws-buzz/docker-compose.yml`;
2. compila il backend con Maven 3.8.6 + JDK 21 (`mvn clean package`, test compresi);
3. compila il frontend (`npm ci` + `ng build`);
4. crea un archivio con `app.jar`, `browser/`, `nginx.conf`, `RELEASE.txt`;
5. lo carica sul server in `/opt/ws-buzz/releases/<data-ora>`, sposta `current` e ricrea i container;
6. conserva le ultime 5 release e verifica `https://<dominio>/api/health`.

Preparazione del server: vedi `DEPLOY_DOCKER_BUILD_PC.md` (fasi A–F).

Argomenti passati al comando: `$ARGUMENTS` (vanno inoltrati allo script così come sono).

## Sequenza da eseguire

1. **Ferma eventuali istanze locali** che bloccano `mvn clean` / `npm ci`: cerca processi
   `java.exe` del backend (come in `/run-be`) e processi `node.exe` con `ng serve`
   nella command line; se li trovi, terminali e segnalalo all'utente:
   ```powershell
   Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='node.exe'" |
     Where-Object { $_.CommandLine -match 'ws_buzz_be|spring-boot:run|ng serve' } |
     Select-Object ProcessId, Name, CommandLine
   ```
2. **Apri una NUOVA FINESTRA PowerShell separata** che esegue lo script con gli argomenti:
   ```powershell
   Start-Process powershell -ArgumentList '-NoExit','-ExecutionPolicy','Bypass','-File','C:\ws_buzz\ws_buzz_be\scripts\deploy-ws-buzz.ps1' <argomenti>
   ```
   (aggiungi ogni argomento di `$ARGUMENTS` come elemento separato di `-ArgumentList`).
3. **Non bloccare la sessione corrente**: il deploy gira nel nuovo terminale.
4. Riporta all'utente che il deploy è stato lanciato, eventuali processi chiusi, e
   ricorda che **il deploy riavvia il backend e chiude tutte le stanze attive**.
