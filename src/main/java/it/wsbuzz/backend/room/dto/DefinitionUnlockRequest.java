package it.wsbuzz.backend.room.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Payload WebSocket per lo sblocco dell'area definizione da parte dell'host.
 * Se {@code playerId} e' nullo, l'host sta sbloccando l'area di tutti i giocatori.
 */
public record DefinitionUnlockRequest(@NotBlank String requesterId, String playerId) {}
