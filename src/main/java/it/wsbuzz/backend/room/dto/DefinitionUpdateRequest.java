package it.wsbuzz.backend.room.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Payload WebSocket per l'aggiornamento in tempo reale del testo della definizione. */
public record DefinitionUpdateRequest(@NotBlank String playerId, @Size(max = 1000) String text) {}
