package it.wsbuzz.backend.room.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Payload WebSocket per la modifica del punteggio di un concorrente da parte
 * dell'host. {@code delta} vale +1 (tasto "+") o -1 (tasto "-").
 */
public record ScoreRequest(@NotBlank String requesterId, @NotBlank String playerId, int delta) {}
