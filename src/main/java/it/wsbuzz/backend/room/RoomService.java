package it.wsbuzz.backend.room;

import it.wsbuzz.backend.room.dto.PlayerView;
import it.wsbuzz.backend.room.dto.RoomJoinedResponse;
import it.wsbuzz.backend.room.dto.RoomView;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Registro di tutte le stanze attive, interamente in memoria (nessun database:
 * scelta deliberata per questa fase del progetto, vedi STRUTTURA_PROGETTO.md).
 * Un riavvio del processo azzera tutte le stanze.
 */
@Service
public class RoomService {

  private static final Logger log = LoggerFactory.getLogger(RoomService.class);

  // Escluse 0/O/1/I per evitare ambiguita' quando il codice viene letto ad alta voce.
  private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  private static final int CODE_LENGTH = 4;

  private final Map<String, Room> rooms = new ConcurrentHashMap<>();
  private final SecureRandom random = new SecureRandom();

  public RoomJoinedResponse createRoom(String hostName) {
    String code = generateUniqueCode();
    Room room = new Room(code);
    PlayerView host = room.addHost(hostName);
    rooms.put(code, room);
    log.info("Stanza creata: code={} host={} (playerId={})", code, hostName, host.id());
    return new RoomJoinedResponse(room.snapshot(), host.id());
  }

  public RoomJoinedResponse joinRoom(String code, String playerName) {
    Room room = requireRoom(code);
    PlayerView player = room.addPlayer(playerName);
    log.info(
        "Giocatore unito: code={} player={} (playerId={}) totale={}",
        code,
        playerName,
        player.id(),
        room.snapshot().players().size());
    return new RoomJoinedResponse(room.snapshot(), player.id());
  }

  public RoomView getRoom(String code) {
    return requireRoom(code).snapshot();
  }

  public RoomView buzz(String code, String playerId) {
    Room room = requireRoom(code);
    room.buzz(playerId);
    log.info("Buzz: code={} playerId={}", code, playerId);
    return room.snapshot();
  }

  public RoomView resetRound(String code, String requesterId) {
    Room room = requireRoom(code);
    requireHost(room, requesterId);
    room.resetRound();
    log.info("Round resettato: code={} host={}", code, requesterId);
    return room.snapshot();
  }

  public RoomView setLocked(String code, String requesterId, boolean locked) {
    Room room = requireRoom(code);
    requireHost(room, requesterId);
    room.setLocked(locked);
    log.info("Lock buzzer: code={} host={} locked={}", code, requesterId, locked);
    return room.snapshot();
  }

  public RoomView setMode(String code, String requesterId, RoomMode mode) {
    Room room = requireRoom(code);
    requireHost(room, requesterId);
    room.setMode(mode);
    log.info("Modalita' stanza: code={} host={} mode={}", code, requesterId, mode);
    return room.snapshot();
  }

  public RoomView updateDefinition(String code, String playerId, String text) {
    Room room = requireRoom(code);
    room.updateDefinition(playerId, text);
    return room.snapshot();
  }

  public RoomView confirmDefinition(String code, String playerId) {
    Room room = requireRoom(code);
    room.confirmDefinition(playerId);
    log.info("Definizione confermata: code={} playerId={}", code, playerId);
    return room.snapshot();
  }

  public RoomView selectChoice(String code, String playerId, String choice) {
    Room room = requireRoom(code);
    room.selectChoice(playerId, choice);
    log.info("Scelta registrata: code={} playerId={} choice={}", code, playerId, choice);
    return room.snapshot();
  }

  public RoomView unlockDefinition(String code, String requesterId, String targetPlayerId) {
    Room room = requireRoom(code);
    requireHost(room, requesterId);
    if (targetPlayerId == null) {
      room.unlockAllDefinitions();
      log.info("Definizioni sbloccate per tutti: code={} host={}", code, requesterId);
    } else {
      room.unlockDefinition(targetPlayerId);
      log.info(
          "Definizione sbloccata: code={} host={} playerId={}", code, requesterId, targetPlayerId);
    }
    return room.snapshot();
  }

  /** Sblocca la scelta di un giocatore (o di tutti, se {@code targetPlayerId} e' null). Solo host. */
  public RoomView unlockChoice(String code, String requesterId, String targetPlayerId) {
    Room room = requireRoom(code);
    requireHost(room, requesterId);
    if (targetPlayerId == null) {
      room.unlockAllChoices();
      log.info("Scelte sbloccate per tutti: code={} host={}", code, requesterId);
    } else {
      room.unlockChoice(targetPlayerId);
      log.info("Scelta sbloccata: code={} host={} playerId={}", code, requesterId, targetPlayerId);
    }
    return room.snapshot();
  }

  /** Aumenta o diminuisce di 1 il punteggio di un concorrente. Solo host. */
  public RoomView adjustScore(String code, String requesterId, String targetPlayerId, int delta) {
    Room room = requireRoom(code);
    requireHost(room, requesterId);
    if (delta != 1 && delta != -1) {
      throw new InvalidRoomOperationException("Il punteggio puo' variare solo di +1 o -1");
    }
    room.adjustScore(targetPlayerId, delta);
    log.info(
        "Punteggio modificato: code={} host={} playerId={} delta={}",
        code,
        requesterId,
        targetPlayerId,
        delta);
    return room.snapshot();
  }

  /**
   * Un giocatore lascia la stanza. Se era l'host, per semplicita' la stanza
   * viene chiusa (nessun trasferimento di ruolo host in questa fase). Se la
   * stanza resta vuota viene comunque rimossa per liberare memoria.
   */
  public RoomView leaveRoom(String code, String playerId) {
    Room room = requireRoom(code);
    if (room.isHost(playerId)) {
      rooms.remove(code);
      log.info("Stanza chiusa: code={} (host {} ha lasciato)", code, playerId);
      return null;
    }
    room.removePlayer(playerId);
    log.info("Giocatore uscito: code={} playerId={}", code, playerId);
    if (room.isEmpty()) {
      rooms.remove(code);
      log.info("Stanza rimossa perche' vuota: code={}", code);
      return null;
    }
    return room.snapshot();
  }

  private void requireHost(Room room, String requesterId) {
    if (!room.isHost(requesterId)) {
      log.warn(
          "Operazione rifiutata: code={} requesterId={} non e' host",
          room.snapshot().code(),
          requesterId);
      throw new InvalidRoomOperationException("Solo l'host puo' compiere questa azione");
    }
  }

  private Room requireRoom(String code) {
    Room room = rooms.get(code);
    if (room == null) {
      log.warn("Stanza non trovata: code={}", code);
      throw new RoomNotFoundException(code);
    }
    return room;
  }

  private String generateUniqueCode() {
    String code;
    do {
      code = randomCode();
    } while (rooms.containsKey(code));
    return code;
  }

  private String randomCode() {
    StringBuilder sb = new StringBuilder(CODE_LENGTH);
    for (int i = 0; i < CODE_LENGTH; i++) {
      sb.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
    }
    return sb.toString();
  }
}
