package uz.inha.tickets.domain;

import static uz.inha.tickets.domain.Enums.*;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "bookings", uniqueConstraints = @UniqueConstraint(columnNames = { "user_id", "idempotencyKey" }))
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @ManyToOne(optional = false)
    public UserAccount user;

    @ManyToOne(optional = false)
    public Event event;

    @Column(nullable = false)
    public String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public BookingStatus status = BookingStatus.CONFIRMED;

    public long totalCents;
    public Instant createdAt = Instant.now();
    public Instant cancelledAt;
    public long refundCents;

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    public List<BookingSeat> seats = new ArrayList<>();

    @OneToOne(mappedBy = "booking", fetch = FetchType.EAGER)
    public Refund refund;

    protected Booking() {}

    public Booking(UserAccount u, Event e, String k) {
        user = u;
        event = e;
        idempotencyKey = k;
    }
}
