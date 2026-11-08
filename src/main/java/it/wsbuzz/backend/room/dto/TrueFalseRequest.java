package it.wsbuzz.backend.room.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Payload WebSocket con cui l'host sceglie se in scelta binaria le due
 * risposte sono mostrate come A/B ({@code false}) o come Vero/Falso ({@code true}).
 */
public record TrueFalseRequest(@NotBlank String requesterId, boolean trueFalse) {}
