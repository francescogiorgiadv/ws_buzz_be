package it.wsbuzz.backend.room.dto;

import java.util.List;

/**
 * Snapshot immutabile di una stanza, esposto via REST/WebSocket.
 * Rispecchia 1:1 {@code Room} lato frontend (room.model.ts).
 */
public record RoomView(
    String code, String hostId, long createdAt, boolean locked, List<PlayerView> players) {}
