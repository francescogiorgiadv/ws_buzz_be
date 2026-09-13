package it.wsbuzz.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
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
 * </ul>
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

  private final String[] allowedOrigins;

  public WebSocketConfig(@Value("${app.cors.allowed-origins}") String allowedOrigins) {
    this.allowedOrigins = allowedOrigins.split(",");
  }

  @Override
  public void configureMessageBroker(MessageBrokerRegistry registry) {
    // Broker in-memory: sufficiente per una singola istanza. Se in futuro si
    // scala su piu' istanze, va sostituito con un relay esterno (es. Redis/RabbitMQ).
    registry.enableSimpleBroker("/topic");
    registry.setApplicationDestinationPrefixes("/app");
  }

  @Override
  public void registerStompEndpoints(StompEndpointRegistry registry) {
    registry.addEndpoint("/ws").setAllowedOrigins(allowedOrigins);
  }
}
