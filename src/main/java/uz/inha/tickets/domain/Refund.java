package uz.inha.tickets.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refunds")
public class Refund {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @OneToOne(optional = false)
    public Booking booking;

    @Column(nullable = false)
    public long amountCents;

    @Column(nullable = false)
    public Instant refundedAt = Instant.now();

    protected Refund() {}

    public Refund(Booking b, long amount) {
        booking = b;
        amountCents = amount;
    }
}
