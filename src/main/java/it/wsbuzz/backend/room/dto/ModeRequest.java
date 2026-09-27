package it.wsbuzz.backend.room.dto;

import it.wsbuzz.backend.room.RoomMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Payload WebSocket per il cambio di modalita' della stanza da parte dell'host. */
public record ModeRequest(@NotBlank String requesterId, @NotNull RoomMode mode) {}
