package it.wsbuzz.backend.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Canale real-time (STOMP su WebSocket) usato per gli eventi live di una stanza:
 * buzz, join/leave, lock/reset. Le operazioni non real-time (creazione stanza,
 * join iniziale) restano su REST, vedi {@link it.wsbuzz.backend.room.RoomRestController}.
 *
 * <p>Convenzioni:
 * <ul>
 *   <li>i client inviano messaggi su {@code /app/rooms/{code}/...}</li>
 *   <li>il server pubblica gli aggiornamenti di stato su {@code /topic/rooms/{code}}</li>
 *   <li>gli errori arrivano solo al client che li ha causati, su {@code /user/queue/errors}</li>
 * </ul>
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

  // Intervallo heartbeat STOMP (ms), in entrambe le direzioni. Allineato al
  // default del client @stomp/stompjs (10s/10s).
  private static final long HEARTBEAT_MS = 10_000;

  private final String[] allowedOrigins;
  private final TaskScheduler messageBrokerTaskScheduler;

  // @Lazy: lo scheduler e' definito dalla stessa configurazione STOMP che
  // questo bean contribuisce a costruire, va risolto solo al primo uso.
  public WebSocketConfig(
      @Value("${app.cors.allowed-origins}") String allowedOrigins,
      @Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler messageBrokerTaskScheduler) {
    this.allowedOrigins = allowedOrigins.split(",");
    this.messageBrokerTaskScheduler = messageBrokerTaskScheduler;
  }

  @Override
  public void configureMessageBroker(MessageBrokerRegistry registry) {
    // Broker in-memory: sufficiente per una singola istanza. Se in futuro si
    // scala su piu' istanze, va sostituito con un relay esterno (es. Redis/RabbitMQ).
    // Heartbeat lato server: tiene viva la connessione attraverso il reverse
    // proxy (che altrimenti la chiude dopo il suo idle timeout, es. 60s di
    // default in Nginx) e fa scoprire al server i client spariti senza leave.
    // "/queue" serve alle code per-sessione degli errori (@SendToUser("/queue/errors"),
    // lato client sottoscrizione a /user/queue/errors).
    registry
        .enableSimpleBroker("/topic", "/queue")
        .setHeartbeatValue(new long[] {HEARTBEAT_MS, HEARTBEAT_MS})
        .setTaskScheduler(messageBrokerTaskScheduler);
    registry.setApplicationDestinationPrefixes("/app");
  }

  @Override
  public void registerStompEndpoints(StompEndpointRegistry registry) {
    registry.addEndpoint("/ws").setAllowedOrigins(allowedOrigins);
  }
}
