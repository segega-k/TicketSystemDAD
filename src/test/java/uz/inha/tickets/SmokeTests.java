package uz.inha.tickets;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import java.time.Instant;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import uz.inha.tickets.domain.*;
import uz.inha.tickets.repo.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SmokeTests {

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

    @Test
    void registerLoginAndListEvents() throws Exception {
        mvc
            .perform(
                post("/api/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"a@b.com\",\"password\":\"password123\",\"displayName\":\"A\"}")
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").exists());
        mvc.perform(get("/api/events")).andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void organizerCanCreateSeatMap() throws Exception {
        var org = users.save(new UserAccount("org@test.com", "x", Enums.Role.ORGANIZER, "Org"));
        var ev = events.save(new Event(org, "T", "D", Instant.now().plusSeconds(99999), "V", "C"));
        seats.save(new Seat(ev, "MAIN", "A", 1, 100));
        assertThat(seats.findByEventIdOrderBySectionAscRowLabelAscSeatNumberAsc(ev.id)).hasSize(1);
    }
}
