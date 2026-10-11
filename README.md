# ws-buzz backend

Backend real-time per [ws-buzz](../README.md): stanze, join dei giocatori e
prenotazioni (buzz) in tempo reale.

## Stack

- **Java 21** (virtual threads abilitati, `spring.threads.virtual.enabled=true`)
- **Spring Boot 4.1.1**
- **REST** per le operazioni one-shot: creazione stanza, join
- **WebSocket (STOMP)** per gli eventi live: buzz, reset round, lock, cambio modalita',
  scelta multipla, definizioni, leave
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

## Deploy dietro reverse proxy

In produzione il backend gira in HTTP sulla 8080 dietro un reverse proxy
(Nginx/Caddy) che gestisce HTTPS. Lato backend e' gia' configurato:

- `server.forward-headers-strategy=native`: gli header `X-Forwarded-*` del proxy
  sono accettati (solo da IP interni) per ricostruire schema/host/IP reali;
- heartbeat STOMP lato server ogni 10s ([`WebSocketConfig`](src/main/java/it/wsbuzz/backend/config/WebSocketConfig.java)),
  cosi' il proxy non chiude il WebSocket per inattivita'.

Porta e indirizzo di ascolto si cambiano senza toccare i file con le variabili
d'ambiente standard di Spring `SERVER_PORT` e `SERVER_ADDRESS` (es.
`SERVER_ADDRESS=127.0.0.1` se il proxy e' sulla stessa macchina, per non esporre
la 8080 all'esterno).

Esempio Nginx (il WebSocket richiede gli header `Upgrade`/`Connection`):

```nginx
location /api/ {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}

location /ws {
    proxy_pass http://127.0.0.1:8080;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_read_timeout 120s;
}
```

## Endpoint

### REST — `http://localhost:8080/api/rooms`

| Metodo | Path | Body | Descrizione |
|---|---|---|---|
| POST | `/api/rooms` | `{ "hostName": "..." }` | Crea una stanza, ritorna `{ room, playerId }` |
| POST | `/api/rooms/{code}/join` | `{ "playerName": "..." }` | Un giocatore entra nella stanza |
| GET | `/api/rooms/{code}` | — | Snapshot corrente della stanza (utile per reconnect) |
| GET | `/api/health` | — | Diagnostica dell'installazione: da aprire dal browser con l'URL pubblico, verifica HTTPS/proxy, CORS e profilo (`status` `OK` o `WARN`, con il dettaglio di ogni controllo in `checks`) |

### WebSocket (STOMP) — endpoint `ws://localhost:8080/ws`

Il client invia messaggi su `/app/rooms/{code}/...` e si iscrive a
`/topic/rooms/{code}` per ricevere lo stato aggiornato della stanza dopo ogni
evento. Gli errori arrivano sulla coda utente `/user/queue/errors`.

| Destinazione invio | Payload | Descrizione |
|---|---|---|
| `/app/rooms/{code}/buzz` | `{ "playerId": "..." }` | Prenotazione: in `FASTEST` blocca il buzzer per tutti, in `LEADERBOARD` resta aperto |
| `/app/rooms/{code}/reset` | `{ "playerId": "<hostId>" }` | Solo host: nuovo round, sblocca e azzera buzz e scelte multiple |
| `/app/rooms/{code}/lock` | `{ "requesterId": "<hostId>", "locked": true }` | Solo host: lock/unlock manuale |
| `/app/rooms/{code}/mode` | `{ "requesterId": "<hostId>", "mode": "FASTEST" \| "LEADERBOARD" \| "DEFINITIONS" \| "MULTIPLE_CHOICE" }` | Solo host: cambia la modalita' della stanza |
| `/app/rooms/{code}/choice` | `{ "playerId": "...", "choice": "A" \| "B" \| "C" \| "D" }` | Modalita' `MULTIPLE_CHOICE`: registra la risposta scelta (una sola volta a round) |
| `/app/rooms/{code}/definition/update` | `{ "playerId": "...", "text": "..." }` | Modalita' `DEFINITIONS`: aggiorna live il testo (max 1000 caratteri), non permesso dopo la conferma |
| `/app/rooms/{code}/definition/confirm` | `{ "playerId": "..." }` | Conferma la definizione corrente: da quel momento non e' piu' modificabile |
| `/app/rooms/{code}/definition/unlock` | `{ "requesterId": "<hostId>", "playerId": "..." \| null }` | Solo host: sblocca (e azzera) l'area di un giocatore, o di tutti se `playerId` e' `null` |
| `/app/rooms/{code}/refresh` | `{ "playerId": "..." }` | Richiede un re-broadcast dello snapshot corrente della stanza |
| `/app/rooms/{code}/leave` | `{ "playerId": "..." }` | Il giocatore lascia la stanza (se e' l'host, la stanza si chiude) |

## Note / debito tecnico

- Nessuna autenticazione: `playerId`/`hostId` sono UUID generati alla
  creazione/join e vanno passati dal client ad ogni azione (stesso schema
  "sessione locale" gia' usato dal frontend, vedi `session.service.ts`).
- Se l'host lascia la stanza, la stanza si chiude (nessun trasferimento di
  ruolo host implementato per ora).
- CORS/allowed origins (`app.cors.allowed-origins`): in produzione arrivano dalla
  variabile d'ambiente **obbligatoria** `APP_CORS_ALLOWED_ORIGINS` (origini separate
  da virgola, senza spazi); senza di essa l'applicazione non parte. In dev il valore
  (`http://localhost:4200`) sta in `application-dev.properties`, profilo `dev`
  attivato automaticamente solo da `mvn spring-boot:run`.

  ```bash
  APP_CORS_ALLOWED_ORIGINS=https://buzz.example.com java -jar target/backend-0.0.1-SNAPSHOT.jar
  ```
