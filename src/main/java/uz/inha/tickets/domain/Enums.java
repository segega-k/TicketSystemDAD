package uz.inha.tickets.domain;

public class Enums {

    public enum Role {
        CUSTOMER,
        ORGANIZER,
        ANALYST,
        ADMIN,
    }

    public enum EventStatus {
        DRAFT,
        PUBLISHED,
        CANCELLED,
    }

    public enum SeatStatus {
        AVAILABLE,
        BLOCKED,
    }

    public enum BookingStatus {
        CONFIRMED,
        CANCELLED,
        REFUNDED,
    }
}
