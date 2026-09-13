package it.wsbuzz.backend.room;

/** Operazione non permessa nello stato attuale della stanza (es. buzz mentre bloccata). */
public class InvalidRoomOperationException extends RuntimeException {
  public InvalidRoomOperationException(String message) {
    super(message);
  }
}
