package it.wsbuzz.backend.room.dto;

/** Errore inviato al client, sia via REST (body 4xx) sia via WebSocket (topic errori). */
public record ErrorMessage(String message) {}
