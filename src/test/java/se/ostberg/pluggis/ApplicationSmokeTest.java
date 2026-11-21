package se.ostberg.pluggis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import se.ostberg.pluggis.session.StudySessionService;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Startar hela Pluggis (databas, schemaläggning, WebSocket, alla tjänster) och går igenom
 * föräldravyns API. Claude är avsiktligt inte nåbar – då ska reservplanen användas.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:pluggis;DB_CLOSE_DELAY=-1",
        "pluggis.device-token=test-token",
        "pluggis.anthropic.api-key=test",
        "pluggis.anthropic.base-url=http://127.0.0.1:9",
        "pluggis.speech.base-url=",
        "pluggis.calendar.sync-cron=-",
        "pluggis.reminders.start-cron=-",
        "pluggis.reminders.nudge-cron=-"
})
@AutoConfigureMockMvc
class ApplicationSmokeTest {

    private static final String TOKEN = "X-Pluggis-Token";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    StudySessionService sessions;

    private JsonNode json(String body) throws Exception {
        return mapper.readTree(body);
    }

    @Test
    void rejectsMissingToken() throws Exception {
        mvc.perform(get("/api/homework")).andExpect(status().isUnauthorized());
    }

    @Test
    void servesWebUi() throws Exception {
        mvc.perform(get("/index.html")).andExpect(status().isOk());
        mvc.perform(get("/app.js")).andExpect(status().isOk());
    }

    @Test
    void parentFlowWorksEndToEndWithoutClaude() throws Exception {
        String due = LocalDate.now().plusDays(6).toString();

        // Ny läxa
        String created = mvc.perform(post("/api/homework").header(TOKEN, "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"NO\",\"title\":\"Prov fotosyntes\",\"dueDate\":\"" + due + "\","
                                + "\"steps\":[\"Läs s. 40–48\",\"Läs s. 49–56\",\"Gör begreppslistan\"]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = json(created).path("id").asLong();

        // Gör det till ett prov och planera – Claude svarar inte, så reservplanen ska användas
        mvc.perform(put("/api/homework/" + id).header(TOKEN, "test-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"TEST\",\"replan\":false}"))
                .andExpect(status().isOk());
        String plan = mvc.perform(post("/api/plan/homework/" + id).header(TOKEN, "test-token"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode items = json(plan).path("items");
        assertThat(items.size()).isGreaterThanOrEqualTo(3);
        long firstItem = items.get(0).path("id").asLong();

        // Lista, plan för idag/kommande, bocka av ett moment (ger poäng)
        mvc.perform(get("/api/homework").header(TOKEN, "test-token")).andExpect(status().isOk());
        mvc.perform(get("/api/plan/today").header(TOKEN, "test-token")).andExpect(status().isOk());
        mvc.perform(get("/api/plan/upcoming?days=14").header(TOKEN, "test-token")).andExpect(status().isOk());
        mvc.perform(post("/api/plan/items/" + firstItem + "/done?deviceId=tab5-1").header(TOKEN, "test-token"))
                .andExpect(status().isOk());

        // Egen kortlek, öva ett kort
        String deck = mvc.perform(post("/api/flashcards/decks").header(TOKEN, "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Glosor\",\"subject\":\"Engelska\",\"cards\":[{\"front\":\"hund\",\"back\":\"dog\"},{\"front\":\"katt\",\"back\":\"cat\"}]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long deckId = json(deck).path("id").asLong();
        String card = mvc.perform(get("/api/flashcards/decks/" + deckId + "/next?deviceId=tab5-1").header(TOKEN, "test-token"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long cardId = json(card).path("id").asLong();
        mvc.perform(post("/api/flashcards/cards/" + cardId + "/grade?deviceId=tab5-1").header(TOKEN, "test-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"correct\":true}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/flashcards/decks/" + deckId + "/export.tsv").header(TOKEN, "test-token"))
                .andExpect(status().isOk());

        // Pass
        mvc.perform(post("/api/devices/tab5-1/sessions").header(TOKEN, "test-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"task\":\"Matte\",\"minutes\":15}"))
                .andExpect(status().isOk());
        sessions.tick();
        mvc.perform(post("/api/devices/tab5-1/sessions/active/finish").header(TOKEN, "test-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"summary\":\"klar\"}"))
                .andExpect(status().isOk());

        // Poäng, bonus, utbetalning, statistik
        mvc.perform(post("/api/devices/tab5-1/rewards/bonus").header(TOKEN, "test-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"points\":20,\"reason\":\"Bra jobbat\"}"))
                .andExpect(status().isOk());
        String week = mvc.perform(get("/api/devices/tab5-1/rewards").header(TOKEN, "test-token"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(json(week).path("weekPoints").asLong()).isGreaterThanOrEqualTo(26); // 20 bonus + 3 moment + 3 kort
        mvc.perform(post("/api/devices/tab5-1/rewards/payouts").header(TOKEN, "test-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/devices/tab5-1/rewards/events").header(TOKEN, "test-token")).andExpect(status().isOk());
        String stats = mvc.perform(get("/api/devices/tab5-1/stats?days=14").header(TOKEN, "test-token"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(json(stats).path("totalCardsReviewed").asInt()).isEqualTo(1);
        mvc.perform(get("/api/devices").header(TOKEN, "test-token")).andExpect(status().isOk());

        // iCal-import
        String ics = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:x1\r\nDTSTART;VALUE=DATE:"
                + LocalDate.now().plusDays(3).toString().replace("-", "")
                + "\r\nSUMMARY:Engelska: Glosförhör\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
        String imported = mvc.perform(post("/api/calendar/import").header(TOKEN, "test-token")
                        .contentType("text/calendar").content(ics))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(json(imported).path("imported").asInt()).isEqualTo(1);

        // Ta bort läxan
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/homework/" + id).header(TOKEN, "test-token"))
                .andExpect(status().isNoContent());
    }

    @Test
    void coachAnswersWithFallbackWhenClaudeIsUnreachable() throws Exception {
        String reply = mvc.perform(post("/api/devices/tab5-2/coach").header(TOKEN, "test-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"hej\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(json(reply).path("reply").asText()).isNotBlank();
    }
}
