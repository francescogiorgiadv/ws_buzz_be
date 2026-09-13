package it.wsbuzz.backend.room;

import it.wsbuzz.backend.room.dto.ErrorMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Traduce le eccezioni di dominio in risposte HTTP per gli endpoint REST. */
@RestControllerAdvice
public class RoomExceptionHandler {

  @ExceptionHandler(RoomNotFoundException.class)
  public ResponseEntity<ErrorMessage> handleNotFound(RoomNotFoundException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorMessage(ex.getMessage()));
  }

  @ExceptionHandler(InvalidRoomOperationException.class)
  public ResponseEntity<ErrorMessage> handleInvalidOperation(InvalidRoomOperationException ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorMessage(ex.getMessage()));
  }
}
