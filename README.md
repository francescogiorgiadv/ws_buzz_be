# ws-buzz backend

Backend real-time per [ws-buzz](../README.md): stanze, join dei giocatori e
prenotazioni (buzz) in tempo reale.

## Stack

- **Java 21** (virtual threads abilitati, `spring.threads.virtual.enabled=true`)
- **Spring Boot 4.1.1**
- **REST** per le operazioni one-shot: creazione stanza, join
- **WebSocket (STOMP)** per gli eventi live: buzz, reset round, lock, leave
- **Nessun database**: lo stato delle stanze vive interamente in memoria
  (`ConcurrentHashMap` in [`RoomService`](src/main/java/it/wsbuzz/backend/room/RoomService.java)).
  Un riavvio del processo azzera tutte le stanze attive — scelta deliberata per
  questa fase del progetto (vedi `STRUTTURA_PROGETTO.md` in root).

## Prerequisiti

Serve **JDK 21** installato (es. [Eclipse Temurin 21](https://adoptium.net/temurin/releases/?version=21)).
Non serve installare Maven: il progetto include il Maven Wrapper (`mvnw` /
`mvnw.cmd`), che scarica Maven automaticamente al primo utilizzo.

## Comandi

```bash
# Avvio in dev (porta 8080)
./mvnw spring-boot:run        # Linux/macOS
mvnw.cmd spring-boot:run      # Windows

# Test
./mvnw test

# Build del jar eseguibile
./mvnw clean package
java -jar target/backend-0.0.1-SNAPSHOT.jar
```

## Endpoint

### REST — `http://localhost:8080/api/rooms`

| Metodo | Path | Body | Descrizione |
|---|---|---|---|
| POST | `/api/rooms` | `{ "hostName": "..." }` | Crea una stanza, ritorna `{ room, playerId }` |
| POST | `/api/rooms/{code}/join` | `{ "playerName": "..." }` | Un giocatore entra nella stanza |
| GET | `/api/rooms/{code}` | — | Snapshot corrente della stanza (utile per reconnect) |

### WebSocket (STOMP) — endpoint `ws://localhost:8080/ws`

Il client invia messaggi su `/app/rooms/{code}/...` e si iscrive a
`/topic/rooms/{code}` per ricevere lo stato aggiornato della stanza dopo ogni
evento. Gli errori arrivano sulla coda utente `/user/queue/errors`.

| Destinazione invio | Payload | Descrizione |
|---|---|---|
| `/app/rooms/{code}/buzz` | `{ "playerId": "..." }` | Prenotazione: blocca il buzzer per tutti |
| `/app/rooms/{code}/reset` | `{ "playerId": "<hostId>" }` | Solo host: nuovo round, sblocca e azzera i buzz |
| `/app/rooms/{code}/lock` | `{ "requesterId": "<hostId>", "locked": true }` | Solo host: lock/unlock manuale |
| `/app/rooms/{code}/leave` | `{ "playerId": "..." }` | Il giocatore lascia la stanza (se e' l'host, la stanza si chiude) |

## Note / debito tecnico

- Nessuna autenticazione: `playerId`/`hostId` sono UUID generati alla
  creazione/join e vanno passati dal client ad ogni azione (stesso schema
  "sessione locale" gia' usato dal frontend, vedi `session.service.ts`).
- Se l'host lascia la stanza, la stanza si chiude (nessun trasferimento di
  ruolo host implementato per ora).
- CORS/allowed origins configurati in `application.properties`
  (`app.cors.allowed-origins`), di default puntano al dev server Angular
  (`http://localhost:4200`) — da aggiornare per l'hosting di produzione.
