package it.wsbuzz.backend.room;

import it.wsbuzz.backend.room.dto.ChoiceRequest;
import it.wsbuzz.backend.room.dto.DefinitionUnlockRequest;
import it.wsbuzz.backend.room.dto.DefinitionUpdateRequest;
import it.wsbuzz.backend.room.dto.ErrorMessage;
import it.wsbuzz.backend.room.dto.LockRequest;
import it.wsbuzz.backend.room.dto.ModeRequest;
import it.wsbuzz.backend.room.dto.PlayerActionRequest;
import it.wsbuzz.backend.room.dto.RoomView;
import jakarta.validation.Valid;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

/**
 * Eventi live di una stanza via STOMP. Ogni azione (comprese le richieste di
 * refresh) ripubblica l'intero {@link RoomView} su {@code /topic/rooms/{code}}:
 * per il volume di dati in gioco (poche decine di giocatori) e' piu' semplice
 * e robusto di un diff incrementale.
 */
@Controller
public class RoomWebSocketController {

  private final RoomService roomService;
  private final SimpMessagingTemplate messagingTemplate;

  public RoomWebSocketController(RoomService roomService, SimpMessagingTemplate messagingTemplate) {
    this.roomService = roomService;
    this.messagingTemplate = messagingTemplate;
  }

  @MessageMapping("/rooms/{code}/buzz")
  public void buzz(@DestinationVariable String code, @Valid @Payload PlayerActionRequest request) {
    RoomView room = roomService.buzz(code, request.playerId());
    broadcast(code, room);
  }

  @MessageMapping("/rooms/{code}/reset")
  public void reset(@DestinationVariable String code, @Valid @Payload PlayerActionRequest request) {
    RoomView room = roomService.resetRound(code, request.playerId());
    broadcast(code, room);
  }

  @MessageMapping("/rooms/{code}/lock")
  public void setLocked(@DestinationVariable String code, @Valid @Payload LockRequest request) {
    RoomView room = roomService.setLocked(code, request.requesterId(), request.locked());
    broadcast(code, room);
  }

  @MessageMapping("/rooms/{code}/mode")
  public void setMode(@DestinationVariable String code, @Valid @Payload ModeRequest request) {
    RoomView room = roomService.setMode(code, request.requesterId(), request.mode());
    broadcast(code, room);
  }

  @MessageMapping("/rooms/{code}/choice")
  public void selectChoice(@DestinationVariable String code, @Valid @Payload ChoiceRequest request) {
    RoomView room = roomService.selectChoice(code, request.playerId(), request.choice());
    broadcast(code, room);
  }

  @MessageMapping("/rooms/{code}/definition/update")
  public void updateDefinition(
      @DestinationVariable String code, @Valid @Payload DefinitionUpdateRequest request) {
    RoomView room = roomService.updateDefinition(code, request.playerId(), request.text());
    broadcast(code, room);
  }

  @MessageMapping("/rooms/{code}/definition/confirm")
  public void confirmDefinition(
      @DestinationVariable String code, @Valid @Payload PlayerActionRequest request) {
    RoomView room = roomService.confirmDefinition(code, request.playerId());
    broadcast(code, room);
  }

  @MessageMapping("/rooms/{code}/definition/unlock")
  public void unlockDefinition(
      @DestinationVariable String code, @Valid @Payload DefinitionUnlockRequest request) {
    RoomView room = roomService.unlockDefinition(code, request.requesterId(), request.playerId());
    broadcast(code, room);
  }

  @MessageMapping("/rooms/{code}/refresh")
  public void refresh(@DestinationVariable String code, @Valid @Payload PlayerActionRequest request) {
    RoomView room = roomService.getRoom(code);
    broadcast(code, room);
  }

  @MessageMapping("/rooms/{code}/leave")
  public void leave(@DestinationVariable String code, @Valid @Payload PlayerActionRequest request) {
    RoomView room = roomService.leaveRoom(code, request.playerId());
    if (room == null) {
      // Stanza chiusa (era l'host, oppure ultimo giocatore rimasto).
      messagingTemplate.convertAndSend(topicFor(code), new ErrorMessage("La stanza e' stata chiusa"));
      return;
    }
    broadcast(code, room);
  }

  @MessageExceptionHandler
  @SendToUser("/queue/errors")
  public ErrorMessage handleError(RoomNotFoundException ex) {
    return new ErrorMessage(ex.getMessage());
  }

  @MessageExceptionHandler
  @SendToUser("/queue/errors")
  public ErrorMessage handleError(InvalidRoomOperationException ex) {
    return new ErrorMessage(ex.getMessage());
  }

  private void broadcast(String code, RoomView room) {
    messagingTemplate.convertAndSend(topicFor(code), room);
  }

  private String topicFor(String code) {
    return "/topic/rooms/" + code;
  }
}
