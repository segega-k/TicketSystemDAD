package uz.inha.tickets.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
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
import org.springframework.web.filter.OncePerRequestFilter;
import uz.inha.tickets.repo.UserRepository;
import uz.inha.tickets.service.JwtService;

@Configuration
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain chain(HttpSecurity http, JwtService jwt, UserRepository users) throws Exception {
        return http
            .csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
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
                } catch (Exception ignored) {}
            }
            fc.doFilter(req, res);
        }
    }
}
