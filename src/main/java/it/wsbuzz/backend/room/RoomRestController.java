package it.wsbuzz.backend.room;

import it.wsbuzz.backend.room.dto.CreateRoomRequest;
import it.wsbuzz.backend.room.dto.JoinRoomRequest;
import it.wsbuzz.backend.room.dto.RoomJoinedResponse;
import it.wsbuzz.backend.room.dto.RoomView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operazioni "one-shot" sulle stanze: creazione e join iniziale. Gli eventi
 * live di una stanza (buzz, reset, lock) passano invece dal canale WebSocket,
 * vedi {@link RoomWebSocketController}.
 */
@RestController
@RequestMapping("/api/rooms")
public class RoomRestController {

  private final RoomService roomService;

  public RoomRestController(RoomService roomService) {
    this.roomService = roomService;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public RoomJoinedResponse createRoom(@Valid @RequestBody CreateRoomRequest request) {
    return roomService.createRoom(request.hostName());
  }

  @PostMapping("/{code}/join")
  public RoomJoinedResponse joinRoom(
      @PathVariable String code, @Valid @RequestBody JoinRoomRequest request) {
    return roomService.joinRoom(normalize(code), request.playerName());
  }

  @GetMapping("/{code}")
  public RoomView getRoom(@PathVariable String code) {
    return roomService.getRoom(normalize(code));
  }

  private String normalize(String code) {
    return code.trim().toUpperCase();
  }
}
