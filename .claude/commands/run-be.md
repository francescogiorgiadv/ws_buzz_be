---
description: Esegue "mvn spring-boot:run" sul progetto ws_buzz_be usando JDK 21, in un nuovo terminale PowerShell, chiudendo prima eventuali istanze già in esecuzione
argument-hint:
allowed-tools: Bash, PowerShell
---

# Comando /run-be

Avvia il backend Spring Boot del progetto, usando il JDK indicato sotto
(indipendentemente da cosa sia configurato di default nell'ambiente dell'utente),
sempre in un terminale PowerShell nuovo e dedicato.

## Path fissi

- `WORKDIR` = `C:\ws_buzz\ws_buzz_be`
- `MAVEN_BIN` = `C:\Program Files\apache-maven-3.8.6\bin`
- `JAVA_HOME` = `C:\Program Files\Java\jdk-21.0.2`
- Repository Maven locale = `C:\Users\fragr\.m2\repository` (già configurato in
  `C:\Users\fragr\.m2\settings.xml`)

## Sequenza da eseguire

1. **Controlla se esiste già un processo Java che sta eseguendo questo backend.**
   Cerca i processi `java.exe` la cui command line contiene il working dir del
   progetto (`ws_buzz_be`) o il nome dell'artifact Maven (`backend`), es.:
   ```powershell
   Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
     Where-Object { $_.CommandLine -match 'ws_buzz_be|spring-boot:run' } |
     Select-Object ProcessId, CommandLine
   ```
2. **Se trovi processi corrispondenti**, terminali (e il/i processo/i `java.exe`
   collegati) prima di procedere, così da evitare di avere due istanze del backend
   attive in contemporanea (es. conflitto sulla porta):
   ```powershell
   Stop-Process -Id <ProcessId> -Force
   ```
   Segnala all'utente quali processi hai chiuso.
3. **Scrivi uno script PowerShell temporaneo** (nella scratchpad directory della
   sessione) con questo contenuto, per evitare problemi di escaping:
   ```powershell
   Set-Location 'C:\ws_buzz\ws_buzz_be'
   $env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.2'
   $env:Path = 'C:\Program Files\apache-maven-3.8.6\bin;' + $env:JAVA_HOME + '\bin;' + $env:Path
   mvn spring-boot:run
   ```
4. **Apri una NUOVA FINESTRA PowerShell separata** (`Start-Process powershell`, MAI
   un tab o la sessione corrente) eseguendo lo script appena creato:
   ```powershell
   Start-Process powershell -ArgumentList '-NoExit','-File','<percorso_script>.ps1'
   ```
5. **Non bloccare la sessione corrente**: `mvn spring-boot:run` deve girare nel
   nuovo terminale, non in foreground nella sessione dell'agente.
6. Riporta all'utente che il comando è stato lanciato nella nuova finestra, e se
   sono stati chiusi processi precedenti.

## Note

- `JAVA_HOME` e il `PATH` vengono impostati solo per la sessione PowerShell aperta,
  senza modificare le variabili d'ambiente di sistema.
- Il controllo dei processi esistenti si basa sulla command line di `java.exe`
  (contiene il path del progetto o il goal `spring-boot:run`), non sul solo nome
  del processo, per non chiudere per errore altri processi Java non correlati.
