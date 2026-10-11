package it.wsbuzz.backend.health;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Risposta di {@code GET /api/health}: stato dell'installazione visto dal backend. */
public record HealthResponse(
    String status,
    Map<String, Check> checks,
    String application,
    String version,
    Instant buildTime,
    Instant startedAt,
    Duration uptime,
    String java,
    List<String> activeProfiles,
    List<String> corsAllowedOrigins,
    RequestInfo request,
    int activeRooms,
    String webSocketSessions) {

  /** Esito di un singolo controllo, con una spiegazione leggibile. */
  public record Check(boolean ok, String detail) {}

  /**
   * La richiesta corrente come la ricostruisce il backend dopo gli header
   * {@code X-Forwarded-*} del reverse proxy.
   */
  public record RequestInfo(String origin, String scheme, boolean secure, String clientIp) {}
}
