package it.wsbuzz.backend.room;

import it.wsbuzz.backend.room.dto.PlayerView;
import it.wsbuzz.backend.room.dto.RoomView;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Aggregato mutabile che rappresenta lo stato "live" di una stanza: interamente
 * in memoria, nessuna persistenza (vedi nota architetturale in
 * STRUTTURA_PROGETTO.md). Vive dalla creazione fino a quando l'host la chiude
 * o il processo viene riavviato.
 *
 * <p>Tutte le mutazioni passano da metodi {@code synchronized} su questa
 * istanza: il volume di scrittura per singola stanza (join, buzz, reset) e'
 * basso, quindi un lock a grana grossa e' piu' che sufficiente e tiene la
 * logica semplice da leggere.
 */
public class Room {

  private final String code;
  private final long createdAt;
  private final Map<String, PlayerView> players = new LinkedHashMap<>();
  private String hostId;
  private boolean locked;

  public Room(String code) {
    this.code = code;
    this.createdAt = System.currentTimeMillis();
  }

  public synchronized PlayerView addHost(String name) {
    PlayerView host = new PlayerView(newPlayerId(), name, true, System.currentTimeMillis(), null);
    this.hostId = host.id();
    players.put(host.id(), host);
    return host;
  }

  public synchronized PlayerView addPlayer(String name) {
    PlayerView player =
        new PlayerView(newPlayerId(), name, false, System.currentTimeMillis(), null);
    players.put(player.id(), player);
    return player;
  }

  public synchronized void removePlayer(String playerId) {
    players.remove(playerId);
  }

  /**
   * Registra il buzz di un giocatore. Il primo buzz valido blocca il buzzer
   * per tutti (fino al prossimo {@link #resetRound()} da parte dell'host).
   */
  public synchronized void buzz(String playerId) {
    PlayerView player = requirePlayer(playerId);
    if (locked) {
      throw new InvalidRoomOperationException("Il buzzer e' bloccato, attendi il prossimo round");
    }
    if (player.buzzedAt() != null) {
      throw new InvalidRoomOperationException("Hai gia' prenotato in questo round");
    }
    players.put(playerId, withBuzz(player, System.currentTimeMillis()));
    locked = true;
  }

  /** Nuovo round: sblocca il buzzer e azzera i buzz di tutti i giocatori. */
  public synchronized void resetRound() {
    locked = false;
    players.replaceAll((id, player) -> withBuzz(player, null));
  }

  /** Lock/unlock manuale da parte dell'host, indipendente da un buzz. */
  public synchronized void setLocked(boolean locked) {
    this.locked = locked;
  }

  public synchronized boolean isHost(String playerId) {
    return hostId != null && hostId.equals(playerId);
  }

  public synchronized boolean isEmpty() {
    return players.isEmpty();
  }

  public synchronized RoomView snapshot() {
    return new RoomView(code, hostId, createdAt, locked, List.copyOf(players.values()));
  }

  private PlayerView requirePlayer(String playerId) {
    PlayerView player = players.get(playerId);
    if (player == null) {
      throw new InvalidRoomOperationException("Giocatore non presente nella stanza");
    }
    return player;
  }

  private static PlayerView withBuzz(PlayerView player, Long buzzedAt) {
    return new PlayerView(
        player.id(), player.name(), player.isHost(), player.joinedAt(), buzzedAt);
  }

  private static String newPlayerId() {
    return UUID.randomUUID().toString();
  }
}
