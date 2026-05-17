package uz.inha.tickets.service;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

public class DomainException extends RuntimeException {

    public final HttpStatus status;
    public final Map<String, Object> properties = new LinkedHashMap<>();

    public DomainException(HttpStatus s, String m) {
        super(m);
        status = s;
    }

    public DomainException prop(String key, Object value) {
        if (value != null) properties.put(key, value);
        return this;
    }

    public static DomainException bad(String m) {
        return new DomainException(HttpStatus.BAD_REQUEST, m);
    }

    public static DomainException unauthorized(String m) {
        return new DomainException(HttpStatus.UNAUTHORIZED, m);
    }

    public static DomainException forbidden(String m) {
        return new DomainException(HttpStatus.FORBIDDEN, m);
    }

    public static DomainException notFound(String m) {
        return new DomainException(HttpStatus.NOT_FOUND, m);
    }

    public static DomainException conflict(String m) {
        return new DomainException(HttpStatus.CONFLICT, m);
    }

    public static DomainException unprocessable(String m) {
        return new DomainException(HttpStatus.UNPROCESSABLE_ENTITY, m);
    }

    public static DomainException tooManyRequests(String m) {
        return new DomainException(HttpStatus.TOO_MANY_REQUESTS, m).prop("retry_after_seconds", 60);
    }

    public static DomainException gatewayTimeout(String m) {
        return new DomainException(HttpStatus.GATEWAY_TIMEOUT, m);
    }

    public static DomainException unavailable(String m) {
        return new DomainException(HttpStatus.SERVICE_UNAVAILABLE, m);
    }
}
