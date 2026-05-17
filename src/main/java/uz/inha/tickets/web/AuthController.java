package uz.inha.tickets.web;

import com.fasterxml.jackson.annotation.JsonAlias;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.inha.tickets.service.AuthService;
import uz.inha.tickets.service.DomainException;

@RestController
@RequestMapping({ "/api/auth", "/api/v1/auth" })
@Tag(name = "Authentication")
public class AuthController {

    final AuthService auth;

    public AuthController(AuthService a) {
        auth = a;
    }

    public record Register(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(min = 8, max = 128) String password,
        @Size(max = 32) String role,
        @JsonAlias({ "display_name", "full_name" }) @Size(max = 120) String displayName
    ) {}

    public record Login(@NotBlank @Email @Size(max = 255) String email, @NotBlank @Size(max = 128) String password) {}

    public record Refresh(@NotBlank @Size(max = 4096) @JsonAlias("refresh_token") String refreshToken) {}

    @PostMapping("/register")
    @Operation(summary = "Register customer account")
    @ApiResponse(responseCode = "201", description = "Registered for /api/v1")
    public ResponseEntity<Map<String, Object>> register(@Valid @RequestBody Register r, HttpServletRequest req) {
        HttpStatus status = req.getRequestURI().startsWith("/api/v1/") ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(auth.register(r.email, r.password, r.role, r.displayName));
    }

    @PostMapping("/login")
    @Operation(summary = "Login with email/password")
    public Map<String, Object> login(@Valid @RequestBody Login r) {
        return auth.login(r.email, r.password);
    }

    @PostMapping("/refresh")
    @Operation(summary = "Rotate refresh token and issue a new access JWT")
    public Map<String, Object> refresh(@Valid @RequestBody Refresh r) {
        return auth.refresh(r.refreshToken);
    }

    @PostMapping("/logout")
    @Operation(summary = "Logout by revoking refresh token")
    @ApiResponse(responseCode = "204", description = "Refresh token revoked")
    public ResponseEntity<Void> logout(
        @RequestHeader(name = "X-Refresh-Token", required = false) String headerToken,
        @RequestBody(required = false) Refresh r
    ) {
        String refresh = headerToken != null && !headerToken.isBlank()
            ? headerToken
            : (r == null ? null : r.refreshToken);
        if (refresh == null || refresh.isBlank()) throw DomainException.unauthorized("invalid refresh token");
        auth.logout(refresh);
        return ResponseEntity.noContent().build();
    }
}
