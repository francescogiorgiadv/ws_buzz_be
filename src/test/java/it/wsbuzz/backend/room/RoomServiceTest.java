package it.wsbuzz.backend.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.wsbuzz.backend.room.dto.RoomJoinedResponse;
import it.wsbuzz.backend.room.dto.RoomView;
import org.junit.jupiter.api.Test;

class RoomServiceTest {

  private final RoomService roomService = new RoomService();

  @Test
  void createRoom_addsHostAsFirstPlayer() {
    RoomJoinedResponse created = roomService.createRoom("Host");

    assertThat(created.room().players()).hasSize(1);
    assertThat(created.room().players().getFirst().isHost()).isTrue();
    assertThat(created.room().hostId()).isEqualTo(created.playerId());
    assertThat(created.room().locked()).isFalse();
  }

  @Test
  void joinRoom_addsPlayerToExistingRoom() {
    RoomJoinedResponse created = roomService.createRoom("Host");
    String code = created.room().code();

    RoomJoinedResponse joined = roomService.joinRoom(code, "Player 1");

    assertThat(joined.room().players()).hasSize(2);
    assertThat(joined.room().players()).anyMatch(p -> p.id().equals(joined.playerId()));
  }

  @Test
  void joinRoom_unknownCode_throws() {
    assertThatThrownBy(() -> roomService.joinRoom("ZZZZ", "Player 1"))
        .isInstanceOf(RoomNotFoundException.class);
  }

  @Test
  void buzz_locksRoomAndRejectsSecondBuzz() {
    RoomJoinedResponse created = roomService.createRoom("Host");
    String code = created.room().code();
    RoomJoinedResponse joined = roomService.joinRoom(code, "Player 1");
    RoomJoinedResponse joined2 = roomService.joinRoom(code, "Player 2");

    RoomView afterFirstBuzz = roomService.buzz(code, joined.playerId());
    assertThat(afterFirstBuzz.locked()).isTrue();

    assertThatThrownBy(() -> roomService.buzz(code, joined2.playerId()))
        .isInstanceOf(InvalidRoomOperationException.class);
  }

  @Test
  void buzz_inLeaderboardMode_doesNotLockRoom() {
    RoomJoinedResponse created = roomService.createRoom("Host");
    String code = created.room().code();
    RoomJoinedResponse joined = roomService.joinRoom(code, "Player 1");
    RoomJoinedResponse joined2 = roomService.joinRoom(code, "Player 2");

    roomService.setMode(code, created.playerId(), RoomMode.LEADERBOARD);
    RoomView afterFirstBuzz = roomService.buzz(code, joined.playerId());
    assertThat(afterFirstBuzz.locked()).isFalse();

    RoomView afterSecondBuzz = roomService.buzz(code, joined2.playerId());
    assertThat(afterSecondBuzz.players()).allMatch(p -> p.isHost() || p.buzzedAt() != null);
  }

  @Test
  void setMode_byNonHost_throws() {
    RoomJoinedResponse created = roomService.createRoom("Host");
    String code = created.room().code();
    RoomJoinedResponse joined = roomService.joinRoom(code, "Player 1");

    assertThatThrownBy(() -> roomService.setMode(code, joined.playerId(), RoomMode.LEADERBOARD))
        .isInstanceOf(InvalidRoomOperationException.class);
  }

  @Test
  void resetRound_unlocksAndClearsBuzzes() {
    RoomJoinedResponse created = roomService.createRoom("Host");
    String code = created.room().code();
    RoomJoinedResponse joined = roomService.joinRoom(code, "Player 1");
    roomService.buzz(code, joined.playerId());

    RoomView afterReset = roomService.resetRound(code, created.playerId());

    assertThat(afterReset.locked()).isFalse();
    assertThat(afterReset.players()).allMatch(p -> p.buzzedAt() == null);
  }

  @Test
  void resetRound_byNonHost_throws() {
    RoomJoinedResponse created = roomService.createRoom("Host");
    String code = created.room().code();
    RoomJoinedResponse joined = roomService.joinRoom(code, "Player 1");

    assertThatThrownBy(() -> roomService.resetRound(code, joined.playerId()))
        .isInstanceOf(InvalidRoomOperationException.class);
  }

  @Test
  void leaveRoom_hostLeaving_closesRoom() {
    RoomJoinedResponse created = roomService.createRoom("Host");
    String code = created.room().code();

    RoomView result = roomService.leaveRoom(code, created.playerId());

    assertThat(result).isNull();
    assertThatThrownBy(() -> roomService.getRoom(code)).isInstanceOf(RoomNotFoundException.class);
  }
}
