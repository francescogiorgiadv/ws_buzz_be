package it.wsbuzz.backend.room.dto;

import jakarta.validation.constraints.NotBlank;

/** Payload WebSocket per il lock/unlock manuale del buzzer da parte dell'host. */
public record LockRequest(@NotBlank String requesterId, boolean locked) {}
