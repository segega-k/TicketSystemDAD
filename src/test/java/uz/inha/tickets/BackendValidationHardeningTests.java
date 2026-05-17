package uz.inha.tickets;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import uz.inha.tickets.domain.Enums;
import uz.inha.tickets.domain.Event;
import uz.inha.tickets.domain.Seat;
import uz.inha.tickets.domain.UserAccount;
import uz.inha.tickets.repo.EventRepository;
import uz.inha.tickets.repo.SeatRepository;
import uz.inha.tickets.repo.UserRepository;
import uz.inha.tickets.service.JwtService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BackendValidationHardeningTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper om;

    @Autowired
    UserRepository users;

    @Autowired
    JwtService jwt;

    @Autowired
    EventRepository events;

    @Autowired
    SeatRepository seats;

    @Test
    void authRegisterLoginValidationAndProblemDetails() throws Exception {
        mvc
            .perform(
                post("/api/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"not-an-email\",\"password\":\"short\"}")
            )
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").value("https://tickets.inha.uz/problems/validation-failed"))
            .andExpect(jsonPath("$.title").value("Validation failed"))
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.trace_id").isString())
            .andExpect(jsonPath("$.path").value("/api/auth/register"));

        String email = "auth-" + UUID.randomUUID() + "@test.com";
        String body = "{\"email\":\"" + email + "\",\"password\":\"password123\",\"displayName\":\"A\"}";
        mvc
            .perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").isString())
            .andExpect(jsonPath("$.refreshToken").isString())
            .andExpect(jsonPath("$.user.email").value(email))
            .andExpect(jsonPath("$.user.role").value("CUSTOMER"));

        mvc
            .perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.status").value(409));

        mvc
            .perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"" + email + "\",\"password\":\"wrong-password\"}")
            )
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.type").value("https://tickets.inha.uz/problems/invalid-credentials"));

        mvc
            .perform(
                post("/api/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"email\":\"bad-role-" +
                        UUID.randomUUID() +
                        "@test.com\",\"password\":\"password123\",\"role\":\"ROOT\"}"
                    )
            )
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.type").value("https://tickets.inha.uz/problems/role-escalation-forbidden"));
    }

    @Test
    void organizerCreatesEventAndSeatMapWhileCustomerIsForbidden() throws Exception {
        String organizerToken = registerAndToken("ORGANIZER");
        MvcResult created = mvc
            .perform(
                post("/api/events")
                    .header("Authorization", bearer(organizerToken))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"title\":\"Validation Gala\",\"description\":\"D\",\"startsAt\":\"2030-01-01T20:00:00Z\",\"venueName\":\"Hall\",\"city\":\"Tashkent\",\"rows\":2,\"seatsPerRow\":3,\"priceCents\":1200}"
                    )
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").isString())
            .andReturn();

        UUID eventId = UUID.fromString(om.readTree(created.getResponse().getContentAsString()).get("id").asText());
        mvc
            .perform(get("/api/events/{id}/seat-map", eventId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.eventId").value(eventId.toString()))
            .andExpect(jsonPath("$.seats", hasSize(6)))
            .andExpect(jsonPath("$.bookedSeatIds", hasSize(0)));

        String customerToken = registerAndToken("CUSTOMER");
        mvc
            .perform(
                post("/api/events")
                    .header("Authorization", bearer(customerToken))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"Nope\",\"startsAt\":\"2030-01-01T20:00:00Z\",\"rows\":1,\"seatsPerRow\":1}")
            )
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.type").value("https://tickets.inha.uz/problems/insufficient-role"));
    }

    @Test
    void holdValidationConflictAdjacencyAndSeatCap() throws Exception {
        Event ev = seedEvent("holds", 7, Instant.now().plusSeconds(100_000));
        List<Seat> ss = sortedSeats(ev);
        String userOne = registerAndToken("CUSTOMER");
        String userTwo = registerAndToken("CUSTOMER");

        mvc
            .perform(
                post("/api/holds")
                    .header("Authorization", bearer(userOne))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"eventId\":\"" + ev.id + "\",\"seatIds\":[]}")
            )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("https://tickets.inha.uz/problems/validation-failed"));

        mvc
            .perform(
                post("/api/holds")
                    .header("Authorization", bearer(userOne))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(holdJson(ev.id, ss.get(0).id, ss.get(1).id))
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.holdToken").isString())
            .andExpect(jsonPath("$.seatIds", hasSize(2)));

        mvc
            .perform(
                post("/api/holds")
                    .header("Authorization", bearer(userTwo))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(holdJson(ev.id, ss.get(1).id))
            )
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.type").value("https://tickets.inha.uz/problems/seat-already-held"));

        mvc
            .perform(
                post("/api/holds")
                    .header("Authorization", bearer(userTwo))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(holdJson(ev.id, ss.get(3).id, ss.get(5).id))
            )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail", containsString("adjacent")));

        Event capEvent = seedEvent("cap", 7, Instant.now().plusSeconds(100_000));
        List<Seat> capSeats = sortedSeats(capEvent);
        String cappedUser = registerAndToken("CUSTOMER");
        mvc
            .perform(
                post("/api/holds")
                    .header("Authorization", bearer(cappedUser))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        holdJson(
                            capEvent.id,
                            capSeats.get(0).id,
                            capSeats.get(1).id,
                            capSeats.get(2).id,
                            capSeats.get(3).id,
                            capSeats.get(4).id
                        )
                    )
            )
            .andExpect(status().isOk());
        mvc
            .perform(
                post("/api/holds")
                    .header("Authorization", bearer(cappedUser))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(holdJson(capEvent.id, capSeats.get(5).id, capSeats.get(6).id))
            )
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.type").value("https://tickets.inha.uz/problems/seat-cap-exceeded"));
    }

    @Test
    void bookingIdempotencyDoubleBookPreventionAndCancelWindow() throws Exception {
        String token = registerAndToken("CUSTOMER");
        Event ev = seedEvent("booking", 2, Instant.now().plusSeconds(200_000));
        List<Seat> ss = sortedSeats(ev);
        String holdToken = hold(token, ev.id, ss.get(0).id);
        String idem = "idem-" + UUID.randomUUID();

        MvcResult first = mvc
            .perform(
                post("/api/bookings")
                    .header("Authorization", bearer(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", idem)
                    .content("{\"hold_token\":\"" + holdToken + "\",\"payment_token\":\"MOCK_PAY_OK\"}")
            )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").isString())
            .andExpect(jsonPath("$.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.totalCents").value(100))
            .andReturn();
        JsonNode firstBooking = om.readTree(first.getResponse().getContentAsString());
        String bookingId = firstBooking.get("id").asText();

        mvc
            .perform(
                post("/api/bookings")
                    .header("Authorization", bearer(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", idem)
                    .content("{\"hold_group_id\":\"" + holdToken + "\"}")
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(bookingId));

        mvc
            .perform(
                post("/api/holds")
                    .header("Authorization", bearer(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(holdJson(ev.id, ss.get(0).id))
            )
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.type").value("https://tickets.inha.uz/problems/seat-already-booked"));

        mvc
            .perform(post("/api/bookings/{id}/cancel", bookingId).header("Authorization", bearer(token)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.refundCents").value(100));

        mvc
            .perform(post("/api/bookings/{id}/cancel", bookingId).header("Authorization", bearer(token)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.type").value("https://tickets.inha.uz/problems/booking-already-cancelled"));

        Event soon = seedEvent("soon", 1, Instant.now().plusSeconds(2 * 60 * 60));
        String soonHold = hold(token, soon.id, sortedSeats(soon).getFirst().id);
        MvcResult soonBooking = mvc
            .perform(
                post("/api/bookings")
                    .header("Authorization", bearer(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", "idem-" + UUID.randomUUID())
                    .content("{\"hold_token\":\"" + soonHold + "\"}")
            )
            .andExpect(status().isCreated())
            .andReturn();
        String soonBookingId = om.readTree(soonBooking.getResponse().getContentAsString()).get("id").asText();
        mvc
            .perform(post("/api/bookings/{id}/cancel", soonBookingId).header("Authorization", bearer(token)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.type").value("https://tickets.inha.uz/problems/cancellation-not-allowed"));
    }

    private String registerAndToken(String role) throws Exception {
        String email = role.toLowerCase() + "-" + UUID.randomUUID() + "@test.com";
        Enums.Role requested = Enums.Role.valueOf(role);
        if (requested != Enums.Role.CUSTOMER) {
            UserAccount u = users.save(new UserAccount(email, "x", requested, "T"));
            return jwt.access(u);
        }
        MvcResult result = mvc
            .perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"email\":\"" +
                        email +
                        "\",\"password\":\"password123\",\"role\":\"CUSTOMER\",\"display_name\":\"T\"}"
                    )
            )
            .andExpect(status().isCreated())
            .andReturn();
        return om.readTree(result.getResponse().getContentAsString()).get("access_token").asText();
    }

    private Event seedEvent(String label, int seatCount, Instant startsAt) {
        UserAccount organizer = users.save(
            new UserAccount("org-" + label + "-" + UUID.randomUUID() + "@test.com", "x", Enums.Role.ORGANIZER, "Org")
        );
        Event ev = events.save(
            new Event(organizer, "Event " + label + " " + UUID.randomUUID(), "D", startsAt, "V", "C")
        );
        for (int i = 1; i <= seatCount; i++) {
            seats.save(new Seat(ev, "MAIN", "A", i, 100));
        }
        return ev;
    }

    private List<Seat> sortedSeats(Event ev) {
        return seats.findByEventIdOrderBySectionAscRowLabelAscSeatNumberAsc(ev.id);
    }

    private String hold(String bearerToken, UUID eventId, UUID seatId) throws Exception {
        MvcResult result = mvc
            .perform(
                post("/api/holds")
                    .header("Authorization", bearer(bearerToken))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(holdJson(eventId, seatId))
            )
            .andExpect(status().isOk())
            .andReturn();
        return om.readTree(result.getResponse().getContentAsString()).get("holdToken").asText();
    }

    private String holdJson(UUID eventId, UUID... seatIds) {
        StringBuilder sb = new StringBuilder("{\"eventId\":\"").append(eventId).append("\",\"seatIds\":[");
        for (int i = 0; i < seatIds.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('\"').append(seatIds[i]).append('\"');
        }
        return sb.append("]}").toString();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
