package it.wsbuzz.backend.room;

import it.wsbuzz.backend.room.dto.PlayerView;
import it.wsbuzz.backend.room.dto.RoomView;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

  private static final int MAX_DEFINITION_LENGTH = 1000;
  private static final Set<String> VALID_CHOICES = Set.of("A", "B", "C", "D");

  private final String code;
  private final long createdAt;
  private final Map<String, PlayerView> players = new LinkedHashMap<>();
  private String hostId;
  private boolean locked;
  private RoomMode mode = RoomMode.FASTEST;

  public Room(String code) {
    this.code = code;
    this.createdAt = System.currentTimeMillis();
  }

  public synchronized PlayerView addHost(String name) {
    PlayerView host =
        new PlayerView(
            newPlayerId(), name, true, System.currentTimeMillis(), null, "", false, null);
    this.hostId = host.id();
    players.put(host.id(), host);
    return host;
  }

  public synchronized PlayerView addPlayer(String name) {
    PlayerView player =
        new PlayerView(
            newPlayerId(), name, false, System.currentTimeMillis(), null, "", false, null);
    players.put(player.id(), player);
    return player;
  }

  public synchronized void removePlayer(String playerId) {
    players.remove(playerId);
  }

  /**
   * Registra il buzz di un giocatore. In modalita' {@link RoomMode#FASTEST} il
   * primo buzz valido blocca il buzzer per tutti (fino al prossimo
   * {@link #resetRound()} da parte dell'host); in {@link RoomMode#LEADERBOARD}
   * il buzzer resta aperto cosi' ogni giocatore puo' prenotarsi e formare una
   * classifica.
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
    if (mode == RoomMode.FASTEST) {
      locked = true;
    }
  }

  /** Nuovo round: sblocca il buzzer e azzera i buzz e le scelte di tutti i giocatori. */
  public synchronized void resetRound() {
    locked = false;
    players.replaceAll((id, player) -> withChoice(withBuzz(player, null), null));
  }

  /** Lock/unlock manuale da parte dell'host, indipendente da un buzz. */
  public synchronized void setLocked(boolean locked) {
    this.locked = locked;
  }

  /** Cambia la modalita' di gioco della stanza. Effetto solo sui prossimi buzz. */
  public synchronized void setMode(RoomMode mode) {
    this.mode = mode;
  }

  /**
   * Aggiorna in tempo reale il testo della definizione mentre il giocatore
   * scrive (broadcastato subito, cosi' l'host vede le modifiche live). Non
   * consentito dopo la conferma.
   */
  public synchronized void updateDefinition(String playerId, String text) {
    PlayerView player = requirePlayer(playerId);
    if (player.definitionConfirmed()) {
      throw new InvalidRoomOperationException("La definizione e' gia' stata confermata");
    }
    String value = text == null ? "" : text;
    if (value.length() > MAX_DEFINITION_LENGTH) {
      throw new InvalidRoomOperationException(
          "La definizione supera i " + MAX_DEFINITION_LENGTH + " caratteri");
    }
    players.put(playerId, withDefinition(player, value, false));
  }

  /**
   * Conferma la definizione corrente di un giocatore: da quel momento il
   * testo non e' piu' modificabile finche' l'host non sblocca l'area con
   * {@link #unlockDefinition(String)} o {@link #unlockAllDefinitions()}.
   */
  public synchronized void confirmDefinition(String playerId) {
    PlayerView player = requirePlayer(playerId);
    if (player.definitionConfirmed()) {
      throw new InvalidRoomOperationException("La definizione e' gia' stata confermata");
    }
    players.put(playerId, withDefinition(player, player.definition(), true));
  }

  /** Sblocca l'area definizione di un singolo giocatore, azzerando il testo. */
  public synchronized void unlockDefinition(String playerId) {
    PlayerView player = requirePlayer(playerId);
    players.put(playerId, withDefinition(player, "", false));
  }

  /** Sblocca l'area definizione di tutti i giocatori, azzerando i testi. */
  public synchronized void unlockAllDefinitions() {
    players.replaceAll((id, player) -> withDefinition(player, "", false));
  }

  /**
   * Registra la risposta scelta da un giocatore tra A/B/C/D. Una volta scelta
   * non e' piu' modificabile fino al prossimo {@link #resetRound()}.
   */
  public synchronized void selectChoice(String playerId, String choice) {
    PlayerView player = requirePlayer(playerId);
    if (player.choice() != null) {
      throw new InvalidRoomOperationException("Hai gia' scelto una risposta in questo round");
    }
    if (!VALID_CHOICES.contains(choice)) {
      throw new InvalidRoomOperationException("Risposta non valida");
    }
    players.put(playerId, withChoice(player, choice));
  }

  public synchronized boolean isHost(String playerId) {
    return hostId != null && hostId.equals(playerId);
  }

  public synchronized boolean isEmpty() {
    return players.isEmpty();
  }

  public synchronized RoomView snapshot() {
    return new RoomView(code, hostId, createdAt, locked, mode, List.copyOf(players.values()));
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
        player.id(),
        player.name(),
        player.isHost(),
        player.joinedAt(),
        buzzedAt,
        player.definition(),
        player.definitionConfirmed(),
        player.choice());
  }

  private static PlayerView withDefinition(PlayerView player, String definition, boolean confirmed) {
    return new PlayerView(
        player.id(),
        player.name(),
        player.isHost(),
        player.joinedAt(),
        player.buzzedAt(),
        definition,
        confirmed,
        player.choice());
  }

  private static PlayerView withChoice(PlayerView player, String choice) {
    return new PlayerView(
        player.id(),
        player.name(),
        player.isHost(),
        player.joinedAt(),
        player.buzzedAt(),
        player.definition(),
        player.definitionConfirmed(),
        choice);
  }

  private static String newPlayerId() {
    return UUID.randomUUID().toString();
  }
}
