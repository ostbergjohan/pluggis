package se.ostberg.pluggis.student;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Två elever får aldrig se varandras läxor, plan, material eller flashkort – från något håll. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "pluggis.admin-password=test-password",
        "pluggis.anthropic.api-key=test",
        "pluggis.anthropic.base-url=http://127.0.0.1:9",
        "spring.datasource.url=jdbc:h2:mem:isolation;DB_CLOSE_DELAY=-1",
        "pluggis.speech.base-url=",
        "pluggis.calendar.sync-cron=-",
        "pluggis.reminders.start-cron=-",
        "pluggis.reminders.nudge-cron=-"
})
class StudentIsolationTest {

    @Autowired
    MockMvc mvc;

    // Föräldern: inloggad i föräldravyn (lösenordet sätts från pluggis.admin-password vid start)
    private static jakarta.servlet.http.Cookie parentCookie;

    private jakarta.servlet.http.Cookie parent() throws Exception {
        if (parentCookie == null) {
            parentCookie = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/auth/login")
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"password\":\"test-password\"}"))
                    .andReturn().getResponse().getCookie(se.ostberg.pluggis.config.AdminAuth.COOKIE);
            org.assertj.core.api.Assertions.assertThat(parentCookie).as("inloggning i testet").isNotNull();
        }
        return parentCookie;
    }
    @Autowired
    ObjectMapper mapper;

    private static final String TOKEN = "X-Pluggis-Token";

    private JsonNode json(String s) throws Exception {
        return mapper.readTree(s);
    }

    private String call(MockHttpServletRequestBuilder b) throws Exception {
        return mvc.perform(b).andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String studentId) {
        b.cookie(parent());
        if (studentId != null) {
            b.header("X-Pluggis-Student", studentId);
        }
        return b;
    }

    /** Lägger in läxa + plan + kortlek + material för en elev, med ett unikt ord i allt. */
    private void seed(String student, String word) throws Exception {
        String due = LocalDate.now().plusDays(4).toString();
        long hw = json(call(as(post("/api/homework"), student).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Läxa " + word + "\",\"subject\":\"NO\",\"dueDate\":\"" + due + "\",\"steps\":[\"Del " + word + " 1\",\"Del " + word + " 2\"]}")))
                .path("id").asLong();
        call(as(post("/api/plan/homework/" + hw), student));
        call(as(post("/api/flashcards/decks"), student).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Lek " + word + "\",\"homeworkId\":" + hw + ",\"cards\":[{\"front\":\"Fråga " + word + "\",\"back\":\"Svar\"}]}"));
        var up = multipart("/api/homework/upload")
                .file(new MockMultipartFile("file", "anteckning-" + word + ".txt", "text/plain", ("Material " + word).getBytes(StandardCharsets.UTF_8)));
        up.param("homeworkId", String.valueOf(hw));
        mvc.perform(as(up, student));
    }

    private String everything(String student) throws Exception {
        StringBuilder all = new StringBuilder();
        for (String url : new String[]{"/api/homework?openOnly=false", "/api/plan/today", "/api/plan/upcoming?days=14",
                "/api/plan/progress", "/api/flashcards/decks"}) {
            all.append(url).append(" → ").append(call(as(get(url), student))).append('\n');
        }
        for (JsonNode h : json(call(as(get("/api/homework?openOnly=false"), student)))) {
            all.append("material ").append(h.path("id").asLong()).append(" → ")
                    .append(call(as(get("/api/homework/" + h.path("id").asLong() + "/material"), student))).append('\n');
        }
        for (JsonNode d : json(call(as(get("/api/flashcards/decks"), student)))) {
            all.append("cards → ").append(call(as(get("/api/flashcards/decks/" + d.path("deck").path("id").asLong() + "/cards"), student))).append('\n');
        }
        return all.toString();
    }

    @Test
    void twoStudentsNeverSeeEachOthersData() throws Exception {
        JsonNode list = json(call(as(get("/api/students"), null)));
        String fred = list.get(0).path("id").asText();
        String lisa = json(call(as(post("/api/students"), null).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Lisa\"}"))).path("id").asText();

        seed(fred, "FREDORD");
        seed(lisa, "LISAORD");

        String fredSees = everything(fred);
        String lisaSees = everything(lisa);
        String noHeader = everything(null);

        assertThat(fredSees).contains("FREDORD").doesNotContain("LISAORD");
        assertThat(lisaSees).contains("LISAORD").doesNotContain("FREDORD");
        assertThat(noHeader).doesNotContain("LISAORD");

        // Lisa når inte Freds saker med id heller
        long fredHw = json(call(as(get("/api/homework?openOnly=false"), fred))).get(0).path("id").asLong();
        int st = mvc.perform(as(get("/api/homework/" + fredHw + "/material"), lisa)).andReturn().getResponse().getStatus();
        String body = mvc.perform(as(get("/api/homework/" + fredHw + "/material"), lisa)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).as("status " + st).doesNotContain("FREDORD");

        // Direkt med id: Freds kortlek, Freds planmoment
        long fredDeck = json(call(as(get("/api/flashcards/decks"), fred))).get(0).path("deck").path("id").asLong();
        var next = mvc.perform(as(get("/api/flashcards/decks/" + fredDeck + "/next?practiceAnyway=true"), lisa)).andReturn().getResponse();
        assertThat(next.getContentAsString(StandardCharsets.UTF_8)).as("kortlek via id, status " + next.getStatus()).doesNotContain("FREDORD");
        var cards = mvc.perform(as(get("/api/flashcards/decks/" + fredDeck + "/cards"), lisa)).andReturn().getResponse();
        assertThat(cards.getContentAsString(StandardCharsets.UTF_8)).as("kort via id, status " + cards.getStatus()).doesNotContain("FREDORD");
        long fredItem = json(call(as(get("/api/plan/upcoming?days=14"), fred))).get(0).path("id").asLong();
        var done = mvc.perform(as(post("/api/plan/items/" + fredItem + "/done"), lisa)).andReturn().getResponse();
        assertThat(done.getStatus()).as("bocka av Freds moment som Lisa").isEqualTo(404);
        assertThat(call(as(get("/api/plan/upcoming?days=14"), fred))).contains("\"done\":false");
    }
}
