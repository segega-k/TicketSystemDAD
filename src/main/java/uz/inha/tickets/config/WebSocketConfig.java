package uz.inha.tickets.config;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import uz.inha.tickets.repo.UserRepository;
import uz.inha.tickets.service.JwtService;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    final JwtService jwt;
    final UserRepository users;

    public WebSocketConfig(JwtService jwt, UserRepository users) {
        this.jwt = jwt;
        this.users = users;
    }

    public void registerStompEndpoints(StompEndpointRegistry r) {
        r.addEndpoint("/ws").setAllowedOriginPatterns("*").withSockJS();
    }

    public void configureMessageBroker(MessageBrokerRegistry r) {
        r.enableSimpleBroker("/topic");
        r.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(
            new ChannelInterceptor() {
                @Override
                public Message<?> preSend(Message<?> message, MessageChannel channel) {
                    StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
                    if (StompCommand.CONNECT.equals(accessor.getCommand())) {
                        String raw = first(accessor.getNativeHeader("Authorization"));
                        if (raw == null || raw.isBlank()) raw = first(accessor.getNativeHeader("access_token"));
                        if (raw != null && !raw.isBlank()) {
                            String token = raw.startsWith("Bearer ") ? raw.substring(7) : raw;
                            try {
                                String email = jwt.subject(token);
                                users
                                    .findByEmail(email)
                                    .ifPresentOrElse(
                                        u ->
                                            accessor.setUser(
                                                new UsernamePasswordAuthenticationToken(
                                                    u.email,
                                                    null,
                                                    List.of(new SimpleGrantedAuthority("ROLE_" + u.role.name()))
                                                )
                                            ),
                                        () -> {
                                            throw new AccessDeniedException("invalid websocket token");
                                        }
                                    );
                            } catch (RuntimeException ex) {
                                throw new AccessDeniedException("invalid websocket token", ex);
                            }
                        }
                    }
                    return message;
                }
            }
        );
    }

    static String first(List<String> values) {
        return values == null || values.isEmpty() ? null : values.getFirst();
    }
}
