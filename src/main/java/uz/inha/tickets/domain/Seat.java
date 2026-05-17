package uz.inha.tickets.domain;

import static uz.inha.tickets.domain.Enums.*;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(
    name = "seats",
    uniqueConstraints = @UniqueConstraint(columnNames = { "event_id", "section", "rowLabel", "seatNumber" })
)
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @ManyToOne(optional = false)
    public Event event;

    @Column(nullable = false)
    public String section;

    @Column(nullable = false)
    public String rowLabel;

    @Column(nullable = false)
    public int seatNumber;

    public int x;
    public int y;

    @Column(nullable = false)
    public long priceCents;

    @Enumerated(EnumType.STRING)
    public SeatStatus status = SeatStatus.AVAILABLE;

    protected Seat() {}

    public Seat(Event e, String sec, String row, int num, long price) {
        event = e;
        section = sec;
        rowLabel = row;
        seatNumber = num;
        priceCents = price;
        x = num;
        y = row.charAt(0);
    }
}
