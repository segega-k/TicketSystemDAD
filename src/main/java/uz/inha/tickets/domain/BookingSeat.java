package uz.inha.tickets.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "booking_seats", uniqueConstraints = @UniqueConstraint(columnNames = { "event_id", "seat_id" }))
public class BookingSeat {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @JsonIgnore
    @ManyToOne(optional = false)
    public Booking booking;

    @ManyToOne(optional = false)
    public Event event;

    @ManyToOne(optional = false)
    public Seat seat;

    protected BookingSeat() {}

    public BookingSeat(Booking b, Event e, Seat s) {
        booking = b;
        event = e;
        seat = s;
    }
}
