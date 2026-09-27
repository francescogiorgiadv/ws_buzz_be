package it.wsbuzz.backend.room.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Payload WebSocket con cui un giocatore sceglie una risposta tra A/B/C/D. */
public record ChoiceRequest(@NotBlank String playerId, @NotBlank @Pattern(regexp = "[ABCD]") String choice) {}
