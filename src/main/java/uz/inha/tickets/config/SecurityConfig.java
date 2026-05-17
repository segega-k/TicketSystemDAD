package uz.inha.tickets.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.filter.OncePerRequestFilter;
import uz.inha.tickets.repo.UserRepository;
import uz.inha.tickets.service.JwtService;

@Configuration
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        // BCrypt cost factor 12 per spec §1.5 (Security: "bcrypt(cost=12)").
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    SecurityFilterChain chain(HttpSecurity http, JwtService jwt, UserRepository users) throws Exception {
        return http
            .csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // Defensive response headers — TLS/HSTS is set at the nginx gateway, but we make
            // X-Content-Type-Options, X-Frame-Options and Referrer-Policy explicit at the
            // application layer too so direct-to-backend traffic (smoke tests, debug tunnels)
            // still receives them. Cache-Control: no-store prevents any intermediate from
            // caching JWT-bearing responses.
            .headers(h -> h
                .contentTypeOptions(c -> {})
                .frameOptions(f -> f.deny())
                .referrerPolicy(rp -> rp.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                .cacheControl(cc -> {})
            )
            .authorizeHttpRequests(a ->
                a
                    .requestMatchers(
                        "/api/auth/**",
                        "/api/v1/auth/**",
                        "/api/events",
                        "/api/events/*",
                        "/api/events/*/seat-map",
                        "/api/events/*/seats",
                        "/api/v1/events",
                        "/api/v1/events/*",
                        "/api/v1/events/*/seat-map",
                        "/api/v1/events/*/seats",
                        "/swagger-ui.html",
                        "/swagger-ui/**",
                        "/v3/api-docs/**",
                        "/actuator/health/**",
                        "/ws/**"
                    )
                    .permitAll()
                    .requestMatchers("/actuator/prometheus")
                    .permitAll()
                    .anyRequest()
                    .authenticated()
            )
            .addFilterBefore(new JwtFilter(jwt, users), UsernamePasswordAuthenticationFilter.class)
            .build();
    }

    static class JwtFilter extends OncePerRequestFilter {

        private static final Logger LOG = LoggerFactory.getLogger(JwtFilter.class);

        final JwtService jwt;
        final UserRepository users;

        JwtFilter(JwtService j, UserRepository u) {
            jwt = j;
            users = u;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain fc)
            throws ServletException, IOException {
            String h = req.getHeader("Authorization");
            if (h != null && h.startsWith("Bearer ")) {
                try {
                    String email = jwt.subject(h.substring(7));
                    users
                        .findByEmail(email)
                        .ifPresent(u ->
                            SecurityContextHolder.getContext()
                                .setAuthentication(
                                    new UsernamePasswordAuthenticationToken(
                                        u.email,
                                        null,
                                        List.of(new SimpleGrantedAuthority("ROLE_" + u.role.name()))
                                    )
                                )
                        );
                } catch (Exception e) {
                    // Anonymous request continues unauthenticated; do not echo the token.
                    // Log only the exception class + message at debug level so operators
                    // can correlate via trace_id without leaking the bearer token itself.
                    LOG.debug(
                        "rejected JWT on {} {}: {} ({})",
                        req.getMethod(),
                        req.getRequestURI(),
                        e.getClass().getSimpleName(),
                        e.getMessage()
                    );
                }
            }
            fc.doFilter(req, res);
        }
    }
}
