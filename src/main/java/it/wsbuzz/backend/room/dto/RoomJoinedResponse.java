package it.wsbuzz.backend.room.dto;

/**
 * Risposta a creazione/join di una stanza: lo snapshot della stanza piu'
 * l'id del giocatore appena creato, cosi' il client sa "chi e' lui" tra i
 * {@code players} di {@link RoomView}.
 */
public record RoomJoinedResponse(RoomView room, String playerId) {}
