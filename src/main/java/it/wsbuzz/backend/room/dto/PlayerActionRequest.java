package it.wsbuzz.backend.room.dto;

import jakarta.validation.constraints.NotBlank;

/** Payload WebSocket per azioni identificate solo dal giocatore che le compie (buzz, leave). */
public record PlayerActionRequest(@NotBlank String playerId) {}
