package uz.inha.tickets.web;

import com.fasterxml.jackson.annotation.JsonAlias;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import uz.inha.tickets.config.AdminBootstrap;
import uz.inha.tickets.domain.Enums.Role;
import uz.inha.tickets.domain.UserAccount;
import uz.inha.tickets.repo.UserRepository;
import uz.inha.tickets.service.AuditService;
import uz.inha.tickets.service.DomainException;

@RestController
@RequestMapping({ "/api/admin/users", "/api/v1/admin/users" })
@Tag(name = "Admin: Users")
public class AdminUserController {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final Current current;
    private final AuditService audit;

    public AdminUserController(UserRepository users, PasswordEncoder encoder, Current current, AuditService audit) {
        this.users = users;
        this.encoder = encoder;
        this.current = current;
        this.audit = audit;
    }

    public record CreateUser(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8) String password,
        @NotBlank String role,
        @JsonAlias({ "display_name", "full_name" }) String displayName
    ) {}

    public record UpdateUser(
        @Email String email,
        @Size(min = 8) String password,
        String role,
        @JsonAlias({ "display_name", "full_name" }) String displayName
    ) {}

    @GetMapping
    @Operation(summary = "List manageable users (organizers/analysts by default)")
    public Map<String, Object> list(@RequestParam(required = false) String role) {
        requireAdmin();
        List<UserAccount> all = users.findAll();
        List<Map<String, Object>> items = all
            .stream()
            .filter(u -> {
                if (role != null && !role.isBlank()) {
                    return u.role.name().equalsIgnoreCase(role.trim());
                }
                return u.role == Role.ORGANIZER || u.role == Role.ANALYST;
            })
            .map(AdminUserController::toDto)
            .toList();
        return Map.of("items", items);
    }

    @PostMapping
    @Operation(summary = "Create organizer/analyst account")
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreateUser r) {
        requireAdmin();
        Role role = parseManagedRole(r.role());
        if (users.existsByEmail(r.email())) throw DomainException.conflict("email already registered");
        UserAccount u = new UserAccount(r.email(), encoder.encode(r.password()), role, r.displayName());
        UserAccount saved = users.save(u);
        audit.record(current.user().id, "ADMIN_CREATED_USER", "user", saved.id, "role=" + role);
        return ResponseEntity.status(201).body(toDto(saved));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update organizer/analyst account")
    public Map<String, Object> update(@PathVariable UUID id, @Valid @RequestBody UpdateUser r) {
        UserAccount actor = requireAdmin();
        UserAccount target = users.findById(id).orElseThrow(() -> DomainException.notFound("user not found"));
        guardManaged(target, actor, "modify");

        if (r.email() != null && !r.email().isBlank() && !r.email().equalsIgnoreCase(target.email)) {
            if (users.existsByEmail(r.email())) throw DomainException.conflict("email already registered");
            target.email = r.email();
        }
        if (r.password() != null && !r.password().isBlank()) {
            target.passwordHash = encoder.encode(r.password());
        }
        if (r.role() != null && !r.role().isBlank()) {
            target.role = parseManagedRole(r.role());
        }
        if (r.displayName() != null) {
            target.displayName = r.displayName();
        }
        UserAccount saved = users.save(target);
        audit.record(actor.id, "ADMIN_UPDATED_USER", "user", saved.id, "role=" + saved.role);
        return toDto(saved);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete organizer/analyst account")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        UserAccount actor = requireAdmin();
        UserAccount target = users.findById(id).orElseThrow(() -> DomainException.notFound("user not found"));
        guardManaged(target, actor, "delete");
        users.delete(target);
        audit.record(actor.id, "ADMIN_DELETED_USER", "user", id, "role=" + target.role);
        return ResponseEntity.noContent().build();
    }

    private UserAccount requireAdmin() {
        UserAccount u = current.user();
        if (u.role != Role.ADMIN) throw DomainException.forbidden("admin role required");
        return u;
    }

    private void guardManaged(UserAccount target, UserAccount actor, String verb) {
        if (target.id.equals(actor.id)) throw DomainException.forbidden("cannot " + verb + " your own account");
        if (target.role == Role.ADMIN) throw DomainException.forbidden("cannot " + verb + " an admin account");
        if (AdminBootstrap.DEFAULT_ADMIN_EMAIL.equalsIgnoreCase(target.email)) {
            throw DomainException.forbidden("cannot " + verb + " the default admin");
        }
    }

    private static Role parseManagedRole(String raw) {
        String normalized = raw == null ? "" : raw.trim().toUpperCase();
        if (normalized.equals("ORGANIZER") || normalized.equals("ANALYST")) {
            return Role.valueOf(normalized);
        }
        throw DomainException.bad("role must be ORGANIZER or ANALYST");
    }

    private static Map<String, Object> toDto(UserAccount u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.id);
        m.put("email", u.email);
        m.put("role", u.role.name());
        m.put("display_name", u.displayName);
        m.put("displayName", u.displayName);
        m.put("created_at", u.createdAt == null ? null : u.createdAt.toString());
        m.put("createdAt", u.createdAt == null ? null : u.createdAt.toString());
        return m;
    }
}
