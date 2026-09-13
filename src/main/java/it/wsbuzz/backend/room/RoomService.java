package it.wsbuzz.backend.room;

import it.wsbuzz.backend.room.dto.PlayerView;
import it.wsbuzz.backend.room.dto.RoomJoinedResponse;
import it.wsbuzz.backend.room.dto.RoomView;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Registro di tutte le stanze attive, interamente in memoria (nessun database:
 * scelta deliberata per questa fase del progetto, vedi STRUTTURA_PROGETTO.md).
 * Un riavvio del processo azzera tutte le stanze.
 */
@Service
public class RoomService {

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
    return new RoomJoinedResponse(room.snapshot(), host.id());
  }

  public RoomJoinedResponse joinRoom(String code, String playerName) {
    Room room = requireRoom(code);
    PlayerView player = room.addPlayer(playerName);
    return new RoomJoinedResponse(room.snapshot(), player.id());
  }

  public RoomView getRoom(String code) {
    return requireRoom(code).snapshot();
  }

  public RoomView buzz(String code, String playerId) {
    Room room = requireRoom(code);
    room.buzz(playerId);
    return room.snapshot();
  }

  public RoomView resetRound(String code, String requesterId) {
    Room room = requireRoom(code);
    requireHost(room, requesterId);
    room.resetRound();
    return room.snapshot();
  }

  public RoomView setLocked(String code, String requesterId, boolean locked) {
    Room room = requireRoom(code);
    requireHost(room, requesterId);
    room.setLocked(locked);
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
      return null;
    }
    room.removePlayer(playerId);
    if (room.isEmpty()) {
      rooms.remove(code);
      return null;
    }
    return room.snapshot();
  }

  private void requireHost(Room room, String requesterId) {
    if (!room.isHost(requesterId)) {
      throw new InvalidRoomOperationException("Solo l'host puo' compiere questa azione");
    }
  }

  private Room requireRoom(String code) {
    Room room = rooms.get(code);
    if (room == null) {
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
