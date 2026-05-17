package uz.inha.tickets.web;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import uz.inha.tickets.domain.UserAccount;
import uz.inha.tickets.repo.UserRepository;
import uz.inha.tickets.service.DomainException;

@Component
public class Current {

    final UserRepository users;

    public Current(UserRepository u) {
        users = u;
    }

    public UserAccount user() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof String e) || "anonymousUser".equals(e)) {
            throw new DomainException(HttpStatus.UNAUTHORIZED, "not authenticated");
        }
        return users
            .findByEmail(e)
            .orElseThrow(() -> new DomainException(HttpStatus.UNAUTHORIZED, "not authenticated"));
    }
}
