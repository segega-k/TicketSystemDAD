package uz.inha.tickets;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
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
class ApiV1ContractTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper om;

    @Autowired
    UserRepository users;

    @Autowired
    EventRepository events;

    @Autowired
    SeatRepository seats;

    @Autowired
    JwtService jwt;

    @Test
    void authRefreshLogoutUseV1AndSnakeCase() throws Exception {
        MvcResult registered = mvc
            .perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"email\":\"v1-" +
                        UUID.randomUUID() +
                        "@test.com\",\"password\":\"password123\",\"display_name\":\"V1\",\"role\":\"ORGANIZER\"}"
                    )
            )
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("role-escalation-forbidden"))
            .andReturn();

        registered = mvc
            .perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"email\":\"v1-" +
                        UUID.randomUUID() +
                        "@test.com\",\"password\":\"password123\",\"display_name\":\"V1\"}"
                    )
            )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.access_token").isString())
            .andExpect(jsonPath("$.refresh_token").isString())
            .andExpect(jsonPath("$.user.role").value("CUSTOMER"))
            .andReturn();

        String access = om.readTree(registered.getResponse().getContentAsString()).get("access_token").asText();
        String headerJson = new String(Base64.getUrlDecoder().decode(access.split("\\.")[0]), StandardCharsets.UTF_8);
        assertTrue(headerJson.contains("\"alg\":\"RS256\""));
        String refresh = om.readTree(registered.getResponse().getContentAsString()).get("refresh_token").asText();
        MvcResult refreshed = mvc
            .perform(
                post("/api/v1/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"refresh_token\":\"" + refresh + "\"}")
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.access_token").isString())
            .andExpect(jsonPath("$.refresh_token").isString())
            .andReturn();

        String rotated = om.readTree(refreshed.getResponse().getContentAsString()).get("refresh_token").asText();
        mvc
            .perform(
                post("/api/v1/auth/logout")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"refresh_token\":\"" + rotated + "\"}")
            )
            .andExpect(status().isNoContent());
    }

    @Test
    void seatsHoldReleaseAndSeatMapExposeHeldSeats() throws Exception {
        String userToken = token(Enums.Role.CUSTOMER);
        Event ev = seedEvent("v1-hold", 3, Instant.now().plusSeconds(100_000));
        List<Seat> ss = seats.findByEventIdOrderBySectionAscRowLabelAscSeatNumberAsc(ev.id);

        MvcResult held = mvc
            .perform(
                post("/api/v1/seats/hold")
                    .header(HttpHeaders.AUTHORIZATION, bearer(userToken))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"event_id\":\"" +
                        ev.id +
                        "\",\"seat_ids\":[\"" +
                        ss.get(0).id +
                        "\",\"" +
                        ss.get(1).id +
                        "\"]}"
                    )
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hold_token").isString())
            .andExpect(jsonPath("$.seat_ids", hasSize(2)))
            .andReturn();

        mvc
            .perform(get("/api/v1/events/{id}/seats", ev.id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.held_seat_ids", hasSize(2)))
            .andExpect(jsonPath("$.booked_seat_ids", hasSize(0)));

        String holdToken = om.readTree(held.getResponse().getContentAsString()).get("hold_token").asText();
        mvc
            .perform(
                delete("/api/v1/seats/hold/{holdGroupId}", holdToken).header(
                    HttpHeaders.AUTHORIZATION,
                    bearer(userToken)
                )
            )
            .andExpect(status().isNoContent());
    }

    @Test
    void bookingRequiresIdempotencyMocksPaymentPdfAndCancellationFreesSeat() throws Exception {
        String userToken = token(Enums.Role.CUSTOMER);
        Event ev = seedEvent("v1-book", 2, Instant.now().plusSeconds(100_000));
        Seat seat = seats.findByEventIdOrderBySectionAscRowLabelAscSeatNumberAsc(ev.id).getFirst();
        String firstHold = hold(userToken, ev.id, seat.id);

        mvc
            .perform(
                post("/api/v1/bookings")
                    .header(HttpHeaders.AUTHORIZATION, bearer(userToken))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"hold_token\":\"" + firstHold + "\",\"payment_token\":\"MOCK_PAY_OK\"}")
            )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("idempotency-key-required"));

        mvc
            .perform(
                post("/api/v1/bookings")
                    .header(HttpHeaders.AUTHORIZATION, bearer(userToken))
                    .header("Idempotency-Key", "declined-" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"hold_token\":\"" + firstHold + "\",\"payment_token\":\"MOCK_PAY_DECLINED\"}")
            )
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("payment-declined"));

        String idem = "book-" + UUID.randomUUID();
        MvcResult booked = mvc
            .perform(
                post("/api/v1/bookings")
                    .header(HttpHeaders.AUTHORIZATION, bearer(userToken))
                    .header("Idempotency-Key", idem)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"hold_token\":\"" + firstHold + "\",\"payment_token\":\"MOCK_PAY_OK\"}")
            )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.event_id").value(ev.id.toString()))
            .andExpect(jsonPath("$.total_cents").value(100))
            .andReturn();
        String bookingId = om.readTree(booked.getResponse().getContentAsString()).get("id").asText();

        MvcResult pdf = mvc
            .perform(
                get("/api/v1/bookings/{id}/ticket.pdf", bookingId).header(HttpHeaders.AUTHORIZATION, bearer(userToken))
            )
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("attachment")))
            .andReturn();
        assertTrue(pdf.getResponse().getContentAsString().startsWith("%PDF-1.4"));

        mvc
            .perform(
                post("/api/v1/bookings/{id}/cancel", bookingId).header(HttpHeaders.AUTHORIZATION, bearer(userToken))
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"));

        hold(userToken, ev.id, seat.id); // freed after cancellation
    }

    @Test
    void v1OrganizerCreatesEventFromSpecRows() throws Exception {
        String organizerToken = token(Enums.Role.ORGANIZER);
        MvcResult created = mvc
            .perform(
                post("/api/v1/events")
                    .header(HttpHeaders.AUTHORIZATION, bearer(organizerToken))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"name\":\"Rows Gala\",\"event_date\":\"2030-06-01T19:00:00Z\",\"venue_name\":\"Inha Hall\",\"rows\":[{\"label\":\"A\",\"seat_count\":2,\"tier\":\"VIP\",\"price\":\"120.00\"},{\"label\":\"B\",\"seat_count\":1,\"tier\":\"STANDARD\",\"price\":\"60.00\"}]}"
                    )
            )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.total_seats").value(3))
            .andReturn();
        UUID eventId = UUID.fromString(om.readTree(created.getResponse().getContentAsString()).get("id").asText());
        mvc
            .perform(get("/api/v1/events/{id}/seats", eventId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rows", hasSize(2)))
            .andExpect(jsonPath("$.rows[0].seats", hasSize(2)));
    }

    @Test
    void organizerEventDashboardExistsUnderV1() throws Exception {
        UserAccount organizer = users.save(
            new UserAccount("org-v1-" + UUID.randomUUID() + "@test.com", "x", Enums.Role.ORGANIZER, "Org")
        );
        String organizerToken = jwt.access(organizer);
        Event ev = events.save(new Event(organizer, "Dashboard V1", "D", Instant.now().plusSeconds(100_000), "V", "C"));
        seats.save(new Seat(ev, "MAIN", "A", 1, 100));

        mvc
            .perform(
                get("/api/v1/organizer/events/{id}/dashboard", ev.id).header(
                    HttpHeaders.AUTHORIZATION,
                    bearer(organizerToken)
                )
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.event_id").value(ev.id.toString()))
            .andExpect(jsonPath("$.revenue_cents").value(0));
    }

    private String token(Enums.Role role) {
        UserAccount u = users.save(
            new UserAccount(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@test.com", "x", role, "T")
        );
        return jwt.access(u);
    }

    private Event seedEvent(String label, int seatCount, Instant startsAt) {
        UserAccount organizer = users.save(
            new UserAccount("org-" + label + "-" + UUID.randomUUID() + "@test.com", "x", Enums.Role.ORGANIZER, "Org")
        );
        Event ev = events.save(
            new Event(organizer, "Event " + label + " " + UUID.randomUUID(), "D", startsAt, "V", "C")
        );
        for (int i = 1; i <= seatCount; i++) seats.save(new Seat(ev, "MAIN", "A", i, 100));
        return ev;
    }

    private String hold(String bearerToken, UUID eventId, UUID seatId) throws Exception {
        MvcResult result = mvc
            .perform(
                post("/api/v1/seats/hold")
                    .header(HttpHeaders.AUTHORIZATION, bearer(bearerToken))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"event_id\":\"" + eventId + "\",\"seat_ids\":[\"" + seatId + "\"]}")
            )
            .andExpect(status().isOk())
            .andReturn();
        return om.readTree(result.getResponse().getContentAsString()).get("hold_token").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
