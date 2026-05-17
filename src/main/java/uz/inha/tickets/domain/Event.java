package uz.inha.tickets.domain;

import static uz.inha.tickets.domain.Enums.*;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "events")
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @ManyToOne(optional = false)
    public UserAccount organizer;

    @Column(nullable = false)
    public String title;

    @Column(length = 4000)
    public String description;

    @Column(nullable = false)
    public Instant startsAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public EventStatus status = EventStatus.PUBLISHED;

    public String venueName;
    public String city;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();

    protected Event() {}

    public Event(UserAccount o, String t, String d, Instant s, String v, String c) {
        organizer = o;
        title = t;
        description = d;
        startsAt = s;
        venueName = v;
        city = c;
    }
}
