package it.wsbuzz.backend.room;

/** Nessuna stanza attiva con il codice richiesto (mai esistita, chiusa, o server riavviato). */
public class RoomNotFoundException extends RuntimeException {
  public RoomNotFoundException(String code) {
    super("Nessuna stanza attiva con codice '%s'".formatted(code));
  }
}
