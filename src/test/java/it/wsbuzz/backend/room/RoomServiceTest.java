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
  void adjustScore_incrementsAndDecrementsAndSurvivesReset() {
    RoomJoinedResponse created = roomService.createRoom("Host");
    String code = created.room().code();
    RoomJoinedResponse joined = roomService.joinRoom(code, "Player 1");

    roomService.adjustScore(code, created.playerId(), joined.playerId(), 1);
    roomService.adjustScore(code, created.playerId(), joined.playerId(), 1);
    roomService.adjustScore(code, created.playerId(), joined.playerId(), -1);
    RoomView afterReset = roomService.resetRound(code, created.playerId());

    assertThat(afterReset.players())
        .filteredOn(p -> p.id().equals(joined.playerId()))
        .singleElement()
        .satisfies(p -> assertThat(p.score()).isEqualTo(1));
  }

  @Test
  void adjustScore_byNonHostOrOnHostOrInvalidDelta_throws() {
    RoomJoinedResponse created = roomService.createRoom("Host");
    String code = created.room().code();
    RoomJoinedResponse joined = roomService.joinRoom(code, "Player 1");

    assertThatThrownBy(
            () -> roomService.adjustScore(code, joined.playerId(), joined.playerId(), 1))
        .isInstanceOf(InvalidRoomOperationException.class);
    assertThatThrownBy(
            () -> roomService.adjustScore(code, created.playerId(), created.playerId(), 1))
        .isInstanceOf(InvalidRoomOperationException.class);
    assertThatThrownBy(
            () -> roomService.adjustScore(code, created.playerId(), joined.playerId(), 5))
        .isInstanceOf(InvalidRoomOperationException.class);
  }

  @Test
  void resetScores_setsEveryScoreToZero_andIsHostOnly() {
    RoomJoinedResponse created = roomService.createRoom("Host");
    String code = created.room().code();
    RoomJoinedResponse p1 = roomService.joinRoom(code, "Player 1");
    RoomJoinedResponse p2 = roomService.joinRoom(code, "Player 2");
    roomService.adjustScore(code, created.playerId(), p1.playerId(), 1);
    roomService.adjustScore(code, created.playerId(), p2.playerId(), -1);

    assertThatThrownBy(() -> roomService.resetScores(code, p1.playerId()))
        .isInstanceOf(InvalidRoomOperationException.class);
    RoomView result = roomService.resetScores(code, created.playerId());

    assertThat(result.players()).allSatisfy(p -> assertThat(p.score()).isZero());
  }

  @Test
  void binaryChoice_acceptsOnlyAOrB_andTrueFalseIsHostOnly() {
    RoomJoinedResponse created = roomService.createRoom("Host");
    String code = created.room().code();
    RoomJoinedResponse p1 = roomService.joinRoom(code, "Player 1");
    RoomJoinedResponse p2 = roomService.joinRoom(code, "Player 2");
    roomService.setMode(code, created.playerId(), RoomMode.BINARY_CHOICE);

    assertThatThrownBy(() -> roomService.selectChoice(code, p1.playerId(), "C"))
        .isInstanceOf(InvalidRoomOperationException.class);
    roomService.selectChoice(code, p2.playerId(), "B");
    assertThatThrownBy(() -> roomService.setTrueFalse(code, p1.playerId(), true))
        .isInstanceOf(InvalidRoomOperationException.class);
    RoomView result = roomService.setTrueFalse(code, created.playerId(), true);

    assertThat(result.trueFalse()).isTrue();
    assertThat(result.players())
        .filteredOn(p -> p.id().equals(p2.playerId()))
        .singleElement()
        .satisfies(p -> assertThat(p.choice()).isEqualTo("B"));
  }

  @Test
  void setMode_toDifferentMode_clearsChoices() {
    RoomJoinedResponse created = roomService.createRoom("Host");
    String code = created.room().code();
    RoomJoinedResponse joined = roomService.joinRoom(code, "Player 1");
    roomService.setMode(code, created.playerId(), RoomMode.MULTIPLE_CHOICE);
    roomService.selectChoice(code, joined.playerId(), "D");

    RoomView result = roomService.setMode(code, created.playerId(), RoomMode.BINARY_CHOICE);

    assertThat(result.players()).allSatisfy(p -> assertThat(p.choice()).isNull());
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
