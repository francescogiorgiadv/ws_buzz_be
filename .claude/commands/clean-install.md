---
description: Esegue "mvn clean install" sul progetto ws_buzz_be usando Maven 3.8.6 e JDK 21, in un nuovo terminale PowerShell
argument-hint:
allowed-tools: Bash, PowerShell
---

# Comando /clean-install

Esegue `mvn clean install` sul progetto backend, usando le versioni fisse di Maven e
JDK indicate sotto (indipendentemente da cosa sia configurato di default nell'ambiente
dell'utente).

## Path fissi

- `WORKDIR` = `C:\ws_buzz\ws_buzz_be`
- `MAVEN_BIN` = `C:\Program Files\apache-maven-3.8.6\bin`
- `JAVA_HOME` = `C:\Program Files\Java\jdk-21.0.2`
- Repository Maven locale = `C:\Users\fragr\.m2\repository` (già configurato in
  `C:\Users\fragr\.m2\settings.xml`)

## Sequenza da eseguire

1. **Scrivi uno script PowerShell temporaneo** (es. nella scratchpad directory della
   sessione, o in un file `.ps1` in una cartella temporanea) con questo contenuto,
   così da evitare problemi di escaping delle virgolette annidate:
   ```powershell
   Set-Location 'C:\ws_buzz\ws_buzz_be'
   $env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.2'
   $env:Path = 'C:\Program Files\apache-maven-3.8.6\bin;' + $env:JAVA_HOME + '\bin;' + $env:Path
   mvn clean install
   ```
2. **Apri una NUOVA FINESTRA PowerShell separata** (`Start-Process powershell`, MAI un
   tab della sessione corrente) eseguendo lo script appena creato:
   ```powershell
   Start-Process powershell -ArgumentList '-NoExit','-File','<percorso_script>.ps1'
   ```
3. **Non bloccare la sessione corrente**: `mvn clean install` deve girare nel nuovo
   terminale, non in foreground nella sessione dell'agente.
4. Riporta all'utente che il comando è stato lanciato nella nuova finestra.

## Note

- `JAVA_HOME` e il `PATH` vengono impostati solo per la sessione PowerShell aperta,
  senza modificare le variabili d'ambiente di sistema.
- `settings.xml` in `C:\Users\fragr\.m2\` va creato una sola volta manualmente (non fa
  parte di questo comando).
