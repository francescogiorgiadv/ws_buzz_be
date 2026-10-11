package it.wsbuzz.backend.health;

import it.wsbuzz.backend.health.HealthResponse.Check;
import it.wsbuzz.backend.health.HealthResponse.RequestInfo;
import it.wsbuzz.backend.room.RoomService;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.socket.config.WebSocketMessageBrokerStats;

/**
 * Diagnostica dell'installazione, da aprire dal browser con l'URL pubblico
 * (es. {@code https://buzz.example.com/api/health}): la richiesta attraversa
 * reverse proxy e Nginx come quelle dell'app, quindi la risposta mostra se
 * HTTPS, header {@code X-Forwarded-*}, CORS e profilo sono configurati bene.
 *
 * <p>Non espone segreti: solo configurazione gia' visibile al browser
 * (origini ammesse) e contatori.
 */
@RestController
public class HealthController {

  private static final String DEV_PROFILE = "dev";

  private final RoomService roomService;
  private final Environment environment;
  private final ObjectProvider<BuildProperties> buildProperties;
  private final ObjectProvider<WebSocketMessageBrokerStats> webSocketStats;
  private final List<String> allowedOrigins;

  public HealthController(
      RoomService roomService,
      Environment environment,
      ObjectProvider<BuildProperties> buildProperties,
      ObjectProvider<WebSocketMessageBrokerStats> webSocketStats,
      @Value("${app.cors.allowed-origins}") String allowedOrigins) {
    this.roomService = roomService;
    this.environment = environment;
    this.buildProperties = buildProperties;
    this.webSocketStats = webSocketStats;
    this.allowedOrigins = Arrays.asList(allowedOrigins.split(","));
  }

  @GetMapping("/api/health")
  public HealthResponse health(HttpServletRequest request) {
    RequestInfo requestInfo = requestInfo(request);
    List<String> profiles = Arrays.asList(environment.getActiveProfiles());

    Map<String, Check> checks = new LinkedHashMap<>();
    checks.put("cors", corsCheck(requestInfo.origin()));
    checks.put("https", httpsCheck(request, requestInfo));
    checks.put("profile", profileCheck(profiles));
    boolean allOk = checks.values().stream().allMatch(Check::ok);

    BuildProperties build = buildProperties.getIfAvailable();
    WebSocketMessageBrokerStats wsStats = webSocketStats.getIfAvailable();
    var runtime = ManagementFactory.getRuntimeMXBean();

    return new HealthResponse(
        allOk ? "OK" : "WARN",
        checks,
        environment.getProperty("spring.application.name"),
        build != null ? build.getVersion() : null,
        build != null ? build.getTime() : null,
        Instant.ofEpochMilli(runtime.getStartTime()),
        Duration.ofMillis(runtime.getUptime()).withNanos(0),
        Runtime.version().toString(),
        profiles,
        allowedOrigins,
        requestInfo,
        roomService.activeRoomCount(),
        wsStats != null ? String.valueOf(wsStats.getWebSocketSessionStats()) : null);
  }

  /** Origine (schema://host[:porta]) della richiesta, come la vede il browser. */
  private RequestInfo requestInfo(HttpServletRequest request) {
    String scheme = request.getScheme();
    int port = request.getServerPort();
    boolean defaultPort =
        ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
    String origin = scheme + "://" + request.getServerName() + (defaultPort ? "" : ":" + port);
    return new RequestInfo(origin, scheme, request.isSecure(), request.getRemoteAddr());
  }

  private Check corsCheck(String origin) {
    if (allowedOrigins.contains(origin)) {
      return new Check(true, "L'origine " + origin + " e' tra quelle ammesse");
    }
    return new Check(
        false,
        "L'origine "
            + origin
            + " non e' in APP_CORS_ALLOWED_ORIGINS "
            + allowedOrigins
            + ": correggi la variabile, oppure il proxy non inoltra Host/X-Forwarded-Proto");
  }

  private Check httpsCheck(HttpServletRequest request, RequestInfo info) {
    if (info.secure()) {
      return new Check(true, "Richiesta ricevuta in HTTPS");
    }
    String host = request.getServerName();
    if ("localhost".equals(host) || "127.0.0.1".equals(host)) {
      return new Check(true, "HTTP in locale: va bene solo in sviluppo");
    }
    return new Check(
        false,
        "Il backend vede la richiesta in HTTP: se il sito e' in HTTPS, il proxy non inoltra"
            + " X-Forwarded-Proto (o non arriva da un IP interno)");
  }

  private Check profileCheck(List<String> profiles) {
    if (profiles.contains(DEV_PROFILE)) {
      return new Check(false, "Profilo 'dev' attivo: in produzione non va attivato");
    }
    return new Check(true, "Nessun profilo di sviluppo attivo");
  }
}
