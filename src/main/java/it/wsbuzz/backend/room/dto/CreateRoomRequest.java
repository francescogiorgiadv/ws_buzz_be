package it.wsbuzz.backend.room.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateRoomRequest(@NotBlank @Size(max = 40) String hostName) {}
