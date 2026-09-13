package it.wsbuzz.backend.room.dto;

/**
 * Snapshot immutabile di un giocatore, esposto via REST/WebSocket.
 * Rispecchia 1:1 {@code Player} lato frontend (player.model.ts).
 */
public record PlayerView(String id, String name, boolean isHost, long joinedAt, Long buzzedAt) {}
