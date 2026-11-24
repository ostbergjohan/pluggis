package se.ostberg.pluggis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import se.ostberg.pluggis.session.StudySessionService;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

/**
 * Startar hela Pluggis (databas, schemaläggning, WebSocket, alla tjänster) och går igenom
 * föräldravyns API. Claude är avsiktligt inte nåbar – då ska reservplanen användas.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
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

    @org.springframework.beans.factory.annotation.Autowired
    se.ostberg.pluggis.homework.HomeworkService homeworkService;

    @org.springframework.beans.factory.annotation.Autowired
    se.ostberg.pluggis.settings.StudentProfile studentProfile;

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @org.springframework.beans.factory.annotation.Autowired
    se.ostberg.pluggis.planning.PlanItemRepository planItems;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    StudySessionService sessions;

    @LocalServerPort
    int port;

    private JsonNode json(String body) throws Exception {
        return mapper.readTree(body);
    }

    @org.springframework.beans.factory.annotation.Autowired
    se.ostberg.pluggis.usage.UsageService usageService;

    @Test
    void costsAreRecordedAndOnlyParentSeesThem() throws Exception {
        usageService.recordClaude("coach", "claude-haiku-5-5", false, 2_000_000, 1_000_000, 0, 0);
        usageService.recordClaude("planning", "claude-sonnet-5-5", true, 100_000, 10_000, 0, 0);
        usageService.recordTts("azure", 1234);
        JsonNode c = json(mvc.perform(get("/api/costs").header(TOKEN, "test-token"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        // Haiku: 2 M in × 0,10 + 1 M ut × 0,50 = 0,70 USD = 7 kr
        assertThat(c.path("today").path("sek").asDouble()).isGreaterThanOrEqualTo(7.0);
        // Sonnet via abonnemanget: 0,1 M × 2 + 0,01 M × 10 = 0,30 USD = 3 kr som inte betalats
        assertThat(c.path("today").path("subscriptionSek").asDouble()).isGreaterThanOrEqualTo(3.0);
        assertThat(c.path("azure").path("characters").asLong()).isGreaterThanOrEqualTo(1234);
        assertThat(c.path("days")).hasSizeGreaterThanOrEqualTo(30);
        assertThat(c.path("purposes").toString()).contains("Coachen").contains("Planering");

        String code = json(mvc.perform(get("/api/students").header(TOKEN, "test-token"))
                .andReturn().getResponse().getContentAsString()).get(0).path("code").asText();
        mvc.perform(get("/api/costs").header(TOKEN, code)).andExpect(status().isForbidden());
    }

    @Test
    void claudeModeCanBeCheckedAndRefusesModesThatCannotWork() throws Exception {
        JsonNode st = json(mvc.perform(get("/api/claude").header(TOKEN, "test-token"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(st.path("mode").asText()).isEqualTo("api");
        assertThat(st.has("apiKey")).isTrue();
        // Ingen Claude Code-token i testmiljön → abonnemangsläget nekas med förklaring
        String err = mvc.perform(put("/api/claude").header(TOKEN, "test-token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"claude-code\"}"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(err).contains("CLAUDE_CODE_OAUTH_TOKEN");
        mvc.perform(put("/api/claude").header(TOKEN, "test-token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"nonsens\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void homeworkWeightIsSavedRoundedAndClamped() throws Exception {
        long id = json(mvc.perform(post("/api/homework").header(TOKEN, "test-token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Nationellt prov\"}")).andReturn().getResponse().getContentAsString()).path("id").asLong();
        String r = mvc.perform(put("/api/homework/" + id).header(TOKEN, "test-token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"weight\":87,\"replan\":false}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json(r).path("weight").asInt()).isEqualTo(90);
        r = mvc.perform(put("/api/homework/" + id).header(TOKEN, "test-token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"weight\":500,\"replan\":false}")).andReturn().getResponse().getContentAsString();
        assertThat(json(r).path("weight").asInt()).isEqualTo(100);
        // Utan vikt i anropet ändras den inte
        r = mvc.perform(put("/api/homework/" + id).header(TOKEN, "test-token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Nationellt prov i matte\",\"replan\":false}")).andReturn().getResponse().getContentAsString();
        assertThat(json(r).path("weight").asInt()).isEqualTo(100);
    }

    @Test
    void studentCanPracticeFlashcardsInChatButOnlyAsThemselves() throws Exception {
        JsonNode first = json(mvc.perform(get("/api/students").header(TOKEN, "test-token"))
                .andReturn().getResponse().getContentAsString()).get(0);
        String code = first.path("code").asText();
        String own = first.path("deviceId").asText();
        long deck = json(mvc.perform(post("/api/flashcards/decks").header(TOKEN, "test-token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Glosor mobil\",\"cards\":[{\"front\":\"dog\",\"back\":\"hund\"}]}"))
                .andReturn().getResponse().getContentAsString()).path("id").asLong();

        assertThat(mvc.perform(get("/api/flashcards/decks").header(TOKEN, code)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).contains("Glosor mobil");
        long card = json(mvc.perform(get("/api/flashcards/decks/" + deck + "/next?practiceAnyway=true").header(TOKEN, code))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("id").asLong();
        mvc.perform(post("/api/flashcards/cards/" + card + "/grade?deviceId=nagon-annan&quiet=true").header(TOKEN, code)
                .contentType(MediaType.APPLICATION_JSON).content("{\"correct\":true}")).andExpect(status().isOk());

        // Fortfarande inte redigera kortlekar, och inte läsa någon annans poäng
        mvc.perform(post("/api/flashcards/decks").header(TOKEN, code).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/devices/nagon-annan/rewards").header(TOKEN, code)).andExpect(status().isForbidden());
        mvc.perform(get("/api/devices/" + own + "/rewards").header(TOKEN, code)).andExpect(status().isOk());
    }

    private boolean manualPlan(long homeworkId) throws Exception {
        for (JsonNode x : json(mvc.perform(get("/api/homework?openOnly=false").header(TOKEN, "test-token"))
                .andReturn().getResponse().getContentAsString())) {
            if (x.path("id").asLong() == homeworkId) {
                return x.path("manualPlan").asBoolean();
            }
        }
        throw new AssertionError("Hittar inte läxa " + homeworkId);
    }

    @Test
    void parentCanRearrangePlanByHandAndItSticks() throws Exception {
        String due = LocalDate.now().plusDays(6).toString();
        long id = json(mvc.perform(post("/api/homework").header(TOKEN, "test-token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Dra-prov\",\"dueDate\":\"" + due + "\",\"steps\":[\"Del 1\",\"Del 2\",\"Del 3\"]}"))
                .andReturn().getResponse().getContentAsString()).path("id").asLong();
        JsonNode plan = json(mvc.perform(post("/api/plan/homework/" + id).header(TOKEN, "test-token"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("items");
        assertThat(plan.size()).isGreaterThanOrEqualTo(2);
        long a = plan.get(0).path("id").asLong();
        long b = plan.get(1).path("id").asLong();
        String day = LocalDate.now().plusDays(2).toString();

        // Lägg b före a på samma dag
        JsonNode arranged = json(mvc.perform(put("/api/plan/days/" + day).header(TOKEN, "test-token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[" + b + "," + a + "]}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        java.util.List<Long> order = new java.util.ArrayList<>();
        arranged.forEach(n -> { if (n.path("homeworkId").asLong() == id) order.add(n.path("id").asLong()); });
        assertThat(order).containsSubsequence(b, a);
        assertThat(manualPlan(id)).isTrue();
        String upcoming = mvc.perform(get("/api/plan/upcoming?days=10").header(TOKEN, "test-token")).andReturn().getResponse().getContentAsString();
        assertThat(upcoming.indexOf("\"id\":" + b + ",")).isLessThan(upcoming.indexOf("\"id\":" + a + ","));

        // Inte på provdagen, inte bakåt i tiden, inte med elevkoden
        mvc.perform(put("/api/plan/days/" + due).header(TOKEN, "test-token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[" + a + "]}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/plan/days/" + LocalDate.now().minusDays(1)).header(TOKEN, "test-token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[" + a + "]}")).andExpect(status().isBadRequest());
        String code = json(mvc.perform(get("/api/students").header(TOKEN, "test-token"))
                .andReturn().getResponse().getContentAsString()).get(0).path("code").asText();
        mvc.perform(put("/api/plan/days/" + day).header(TOKEN, code).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[" + a + "]}")).andExpect(status().isForbidden());

        // Ny plan för hand → Pluggis tar över igen
        mvc.perform(post("/api/plan/homework/" + id).header(TOKEN, "test-token")).andExpect(status().isOk());
        assertThat(manualPlan(id)).isFalse();
    }

    @Test
    void pointsCanBeTurnedOffPerStudent() throws Exception {
        long lisa = json(mvc.perform(post("/api/students").header(TOKEN, "test-token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Utan poäng\"}")).andReturn().getResponse().getContentAsString()).path("id").asLong();
        JsonNode created = json(mvc.perform(get("/api/students").header(TOKEN, "test-token")).andReturn().getResponse().getContentAsString());
        String code = null;
        for (JsonNode x : created) {
            if (x.path("id").asLong() == lisa) {
                code = x.path("code").asText();
            }
        }
        assertThat(json(mvc.perform(get("/api/chat/me").header(TOKEN, code)).andReturn().getResponse().getContentAsString())
                .path("pointsEnabled").asBoolean()).isTrue();
        JsonNode st = json(mvc.perform(put("/api/student").header(TOKEN, "test-token").header("X-Pluggis-Student", String.valueOf(lisa))
                .contentType(MediaType.APPLICATION_JSON).content("{\"pointsEnabled\":false}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(st.path("pointsEnabled").asBoolean()).isFalse();
        assertThat(json(mvc.perform(get("/api/chat/me").header(TOKEN, code)).andReturn().getResponse().getContentAsString())
                .path("pointsEnabled").asBoolean()).isFalse();
    }

    @Test
    void chatFilesAreRevalidatedAndChatKnowsItsVersion() throws Exception {
        String cc = mvc.perform(get("/chat.js")).andExpect(status().isOk()).andReturn().getResponse().getHeader("Cache-Control");
        assertThat(cc).contains("no-cache");
        String code = json(mvc.perform(get("/api/students").header(TOKEN, "test-token"))
                .andReturn().getResponse().getContentAsString()).get(0).path("code").asText();
        assertThat(json(mvc.perform(get("/api/chat/me").header(TOKEN, code)).andReturn().getResponse().getContentAsString())
                .path("version").asText()).hasSize(12);
    }

    @Test
    void eachDayIsGroupedPerSubjectWithNearestDeadlineFirst() throws Exception {
        String later = LocalDate.now().plusDays(5).toString();
        String sooner = LocalDate.now().plusDays(4).toString();
        long a = json(mvc.perform(post("/api/homework").header(TOKEN, "test-token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Gruppa A\",\"subject\":\"Engelska\",\"dueDate\":\"" + later + "\",\"steps\":[\"A1\",\"A2\",\"A3\",\"A4\"]}"))
                .andReturn().getResponse().getContentAsString()).path("id").asLong();
        long b = json(mvc.perform(post("/api/homework").header(TOKEN, "test-token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Gruppa B\",\"subject\":\"NO\",\"dueDate\":\"" + sooner + "\",\"steps\":[\"B1\",\"B2\",\"B3\",\"B4\"]}"))
                .andReturn().getResponse().getContentAsString()).path("id").asLong();
        mvc.perform(post("/api/plan/homework/" + a).header(TOKEN, "test-token")).andExpect(status().isOk());
        mvc.perform(post("/api/plan/homework/" + b).header(TOKEN, "test-token")).andExpect(status().isOk());
        JsonNode up = json(mvc.perform(get("/api/plan/upcoming?days=6").header(TOKEN, "test-token")).andReturn().getResponse().getContentAsString());
        java.util.Map<String, java.util.List<Long>> perDay = new java.util.LinkedHashMap<>();
        up.forEach(i -> {
            long hw = i.path("homeworkId").asLong();
            if (hw == a || hw == b) {
                perDay.computeIfAbsent(i.path("planDate").asText(), k -> new java.util.ArrayList<>()).add(hw);
            }
        });
        boolean sawBoth = false;
        for (var day : perDay.values()) {
            // ett block per läxa: aldrig A, B, A
            long switches = java.util.stream.IntStream.range(1, day.size()).filter(i -> !day.get(i).equals(day.get(i - 1))).count();
            assertThat(switches).isLessThanOrEqualTo(1);
            if (day.contains(a) && day.contains(b)) {
                sawBoth = true;
                assertThat(day.get(0)).isEqualTo(b); // närmast deadline först
            }
        }
        assertThat(sawBoth).isTrue();

        // Handordnad dag där A ligger på båda sidor om B → ändå ett block per läxa, i förälderns blockordning
        String day = perDay.entrySet().stream().filter(e -> e.getValue().contains(a) && e.getValue().contains(b))
                .findFirst().orElseThrow().getKey();
        java.util.List<Long> aIds = new java.util.ArrayList<>();
        java.util.List<Long> bIds = new java.util.ArrayList<>();
        up.forEach(i -> {
            if (i.path("planDate").asText().equals(day)) {
                (i.path("homeworkId").asLong() == a ? aIds : i.path("homeworkId").asLong() == b ? bIds : new java.util.ArrayList<Long>())
                        .add(i.path("id").asLong());
            }
        });
        java.util.List<Long> mixed = new java.util.ArrayList<>(aIds.subList(0, 1));
        mixed.addAll(bIds);
        mixed.addAll(aIds.subList(1, aIds.size()));
        if (aIds.size() == 1) {
            mixed.add(0, bIds.get(0));
        }
        mvc.perform(put("/api/plan/days/" + day).header(TOKEN, "test-token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":" + mixed + "}")).andExpect(status().isOk());
        java.util.List<Long> after = new java.util.ArrayList<>();
        json(mvc.perform(get("/api/plan/upcoming?days=6").header(TOKEN, "test-token")).andReturn().getResponse().getContentAsString())
                .forEach(i -> {
                    long hw = i.path("homeworkId").asLong();
                    if (i.path("planDate").asText().equals(day) && (hw == a || hw == b)) {
                        after.add(hw);
                    }
                });
        long switches = java.util.stream.IntStream.range(1, after.size()).filter(k -> !after.get(k).equals(after.get(k - 1))).count();
        assertThat(switches).isLessThanOrEqualTo(1);
    }

    @org.springframework.beans.factory.annotation.Autowired
    se.ostberg.pluggis.flashcards.FlashcardService flashcardService;

    @Test
    void quizCardsCarryTheirAlternativesAndStudentsMayAskForAQuiz() throws Exception {
        var deck = flashcardService.createDeck("⚡ Snabbquiz: test", "NO", null, se.ostberg.pluggis.flashcards.Deck.Source.GENERATED,
                java.util.List.of(new se.ostberg.pluggis.flashcards.FlashcardService.NewCard(
                        "Det gröna färgämnet ___ fångar ljus.", "klorofyll", null, "CLOZE", java.util.List.of("glukos", "stärkelse", "cellulosa"))));
        JsonNode card = json(mvc.perform(get("/api/flashcards/decks/" + deck.getId() + "/next?practiceAnyway=true").header(TOKEN, "test-token"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(card.path("kind").asText()).isEqualTo("CLOZE");
        assertThat(card.path("wrongOptions")).hasSize(3);

        String code = json(mvc.perform(get("/api/students").header(TOKEN, "test-token"))
                .andReturn().getResponse().getContentAsString()).get(0).path("code").asText();
        // Claude nås inte i testet – men eleven ska inte stoppas av behörigheten
        int st = mvc.perform(post("/api/flashcards/quiz").header(TOKEN, code).contentType(MediaType.APPLICATION_JSON)
                .content("{\"topic\":\"Fotosyntes\",\"mode\":\"cloze\"}")).andReturn().getResponse().getStatus();
        assertThat(st).isEqualTo(503); // nådde Claude-steget (inte stoppad av behörigheten), vänligt fel
    }

    @Test
    void studentCanStartExplainingAndUnknownRoundsAreGone() throws Exception {
        String code = json(mvc.perform(get("/api/students").header(TOKEN, "test-token"))
                .andReturn().getResponse().getContentAsString()).get(0).path("code").asText();
        // Claude nås inte i testet: vänligt 503, inte stoppad av behörigheten
        mvc.perform(post("/api/explain/start").header(TOKEN, code).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(post("/api/explain/00000000-0000-0000-0000-000000000000/answer").header(TOKEN, code)
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"hej\"}")).andExpect(status().isNotFound());
    }

    @Test
    void studentCanStartAMockExam() throws Exception {
        String code = json(mvc.perform(get("/api/students").header(TOKEN, "test-token"))
                .andReturn().getResponse().getContentAsString()).get(0).path("code").asText();
        mvc.perform(post("/api/exam/start").header(TOKEN, code).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isServiceUnavailable()); // Claude nås inte i testet – men behörigheten släppte igenom
        mvc.perform(post("/api/exam/00000000-0000-0000-0000-000000000000/submit").header(TOKEN, code)
                .contentType(MediaType.APPLICATION_JSON).content("{\"answers\":{\"1\":\"x\"}}")).andExpect(status().isNotFound());
    }

    @Test
    void studentCanPlayGamesAndGetsPointsOncePerDay() throws Exception {
        JsonNode first = json(mvc.perform(get("/api/students").header(TOKEN, "test-token")).andReturn().getResponse().getContentAsString()).get(0);
        String code = first.path("code").asText();
        String dev = first.path("deviceId").asText();
        mvc.perform(post("/api/games/order").header(TOKEN, code).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isServiceUnavailable()); // Claude nås inte i testet
        long deck = json(mvc.perform(post("/api/flashcards/decks").header(TOKEN, "test-token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Matchlek\",\"cards\":[{\"front\":\"perro\",\"back\":\"hund\"}]}"))
                .andReturn().getResponse().getContentAsString()).path("id").asLong();
        assertThat(mvc.perform(get("/api/flashcards/decks/" + deck + "/cards").header(TOKEN, code)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).contains("perro");
        int before = json(mvc.perform(get("/api/devices/" + dev + "/rewards").header(TOKEN, code)).andReturn().getResponse().getContentAsString())
                .path("weekPoints").asInt();
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/games/done?deviceId=" + dev).header(TOKEN, code).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"kind\":\"match\",\"what\":\"Glosor kap 5\"}")).andExpect(status().isOk());
        }
        int after = json(mvc.perform(get("/api/devices/" + dev + "/rewards").header(TOKEN, code)).andReturn().getResponse().getContentAsString())
                .path("weekPoints").asInt();
        assertThat(after - before).isEqualTo(3);
        mvc.perform(post("/api/games/done").header(TOKEN, code).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"fusk\",\"what\":\"x\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void practiceTimeIsCappedToRealElapsedTime() throws Exception {
        JsonNode first = json(mvc.perform(get("/api/students").header(TOKEN, "test-token")).andReturn().getResponse().getContentAsString()).get(0);
        String code = first.path("code").asText();
        String id = json(mvc.perform(post("/api/practice/start").header(TOKEN, code).contentType(MediaType.APPLICATION_JSON)
                .content("{\"what\":\"Glosor\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("id").asText();
        // Påstår 50 minuter direkt efter start → servern räknar bara den tid som gått (några millisekunder)
        JsonNode r = json(mvc.perform(post("/api/practice/" + id + "/end?deviceId=" + first.path("deviceId").asText()).header(TOKEN, code)
                .contentType(MediaType.APPLICATION_JSON).content("{\"activeSeconds\":3000}")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(r.path("minutes").asInt()).isZero();
        assertThat(r.path("counted").asBoolean()).isFalse();
        // Samma id två gånger går inte
        mvc.perform(post("/api/practice/" + id + "/end").header(TOKEN, code).contentType(MediaType.APPLICATION_JSON)
                .content("{\"activeSeconds\":10}")).andExpect(status().isNotFound());
    }

    @Test
    void rejectsMissingToken() throws Exception {
        mvc.perform(get("/api/homework")).andExpect(status().isUnauthorized());
    }

    @Test
    void servesWebUi() throws Exception {
        mvc.perform(get("/admin")).andExpect(status().isOk()).andExpect(forwardedUrl("/admin.html"));
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("/chat.html"));
        mvc.perform(get("/chat.html")).andExpect(status().isOk());
        mvc.perform(get("/admin.html")).andExpect(status().isOk());
        mvc.perform(get("/index.html")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin"));
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
        assertThat(json(stats).path("totalCardsReviewed").asInt()).isGreaterThanOrEqualTo(1); // andra tester kan också ha rättat kort
        mvc.perform(get("/api/devices").header(TOKEN, "test-token")).andExpect(status().isOk());

        // iCal: förhandsgranska, importera valda, prenumeration med trasig länk
        String d3 = LocalDate.now().plusDays(3).toString().replace("-", "");
        String ics = "BEGIN:VCALENDAR\r\n"
                + "BEGIN:VEVENT\r\nUID:x1\r\nDTSTART;VALUE=DATE:" + d3 + "\r\nSUMMARY:Engelska: Glosförhör\r\nEND:VEVENT\r\n"
                + "BEGIN:VEVENT\r\nUID:x2\r\nDTSTART;VALUE=DATE:" + d3 + "\r\nSUMMARY:Studiedag\r\nEND:VEVENT\r\n"
                + "END:VCALENDAR\r\n";
        String preview = mvc.perform(post("/api/calendar/preview").header(TOKEN, "test-token")
                        .contentType("text/plain").content(ics))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(json(preview).size()).isEqualTo(2);
        mvc.perform(put("/api/calendar").header(TOKEN, "test-token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"webcals://127.0.0.1:9/kalender\",\"onlySchoolwork\":true}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/calendar").header(TOKEN, "test-token")).andExpect(status().isOk());
        String imported = mvc.perform(post("/api/calendar/import?uids=x1").header(TOKEN, "test-token")
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
    void calendarWithChangingUidsAndBrokenEncodingDoesNotCreateDuplicates() throws Exception {
        String date = LocalDate.now().plusDays(10).toString().replace("-", "");
        java.util.function.Function<String, String> feed = (uidSuffix) -> "BEGIN:VCALENDAR\r\n"
                + "BEGIN:VEVENT\r\nUID:a-" + uidSuffix + "\r\nDTSTART;VALUE=DATE:" + date
                + "\r\nSUMMARY:Prov i SO: Vikingatiden\r\nEND:VEVENT\r\n"
                + "BEGIN:VEVENT\r\nUID:b-" + uidSuffix + "\r\nDTSTART;VALUE=DATE:" + date
                + "\r\nSUMMARY:Läxa: Glosor kap 5\r\nEND:VEVENT\r\n"
                + "BEGIN:VEVENT\r\nUID:c-" + uidSuffix + "\r\nDTSTART;VALUE=DATE:" + date
                + "\r\nSUMMARY:Läxa: Glosor kap 5\r\nEND:VEVENT\r\n"  // samma händelse två gånger i flödet
                + "END:VCALENDAR\r\n";

        // Som innan åäö-rättningen: titeln sparad med trasig kodning
        mvc.perform(post("/api/calendar/import").header(TOKEN, "test-token").contentType("text/plain")
                        .content(feed.apply("1").replace("Läxa", "LÃ¤xa")))
                .andExpect(status().isOk());
        // Ny hämtning: nya UID:n och rätt kodning
        mvc.perform(post("/api/calendar/import").header(TOKEN, "test-token").contentType("text/plain")
                        .content(feed.apply("2")))
                .andExpect(status().isOk());
        String third = mvc.perform(post("/api/calendar/import").header(TOKEN, "test-token").contentType("text/plain")
                        .content(feed.apply("3")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        // Samma innehåll med nya UID:n: inget nytt, inget ändrat
        assertThat(json(third).path("created").asInt()).isZero();
        assertThat(json(third).path("updated").asInt()).isZero();

        String list = mvc.perform(get("/api/homework?openOnly=false").header(TOKEN, "test-token"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String due = LocalDate.now().plusDays(10).toString();
        long count = 0;
        boolean glosorOk = false;
        for (JsonNode h : json(list)) {
            if (due.equals(h.path("dueDate").asText())) {
                count++;
                glosorOk |= h.path("title").asText().equals("Läxa: Glosor kap 5");
            }
        }
        assertThat(count).isEqualTo(2);
        assertThat(glosorOk).isTrue();
        mvc.perform(post("/api/calendar/dedupe").header(TOKEN, "test-token")).andExpect(status().isOk());
    }

    @Test
    void calendarMergesWithOwnHomeworkBrokenCharactersAndParallelSyncs() throws Exception {
        String dueIso = LocalDate.now().plusDays(11).toString();
        String date = dueIso.replace("-", "");
        // Läxan fanns redan, inlagd för hand med lite annan stavning
        mvc.perform(post("/api/homework").header(TOKEN, "test-token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"Matte\",\"title\":\"Matteprov - kapitel 3\",\"description\":\"s. 40-60\",\"dueDate\":\"" + dueIso + "\"}"))
                .andExpect(status().isCreated());
        java.util.function.BiFunction<String, String, String> feed = (uid, glosor) -> "BEGIN:VCALENDAR\r\n"
                + "BEGIN:VEVENT\r\nUID:m-" + uid + "\r\nDTSTART;VALUE=DATE:" + date
                + "\r\nSUMMARY:Matteprov: Kapitel 3\r\nEND:VEVENT\r\n"
                + "BEGIN:VEVENT\r\nUID:g-" + uid + "\r\nDTSTART;VALUE=DATE:" + date
                + "\r\nSUMMARY:" + glosor + "\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";

        mvc.perform(post("/api/calendar/import").header(TOKEN, "test-token").contentType("text/plain")
                .content(feed.apply("1", "Glosförhör v.48"))).andExpect(status().isOk());
        mvc.perform(post("/api/calendar/import").header(TOKEN, "test-token").contentType("text/plain")
                .content(feed.apply("2", "Glosf?rh?r v.48"))).andExpect(status().isOk());
        // Två synkar samtidigt (t.ex. timsynk + knapptryck), båda med nya UID:n
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var a = pool.submit(() -> mvc.perform(post("/api/calendar/import").header(TOKEN, "test-token")
                .contentType("text/plain").content(feed.apply("3", "GlosfÃ¶rhÃ¶r v.48"))).andReturn());
        var b = pool.submit(() -> mvc.perform(post("/api/calendar/import").header(TOKEN, "test-token")
                .contentType("text/plain").content(feed.apply("4", "Glosförhör v.48"))).andReturn());
        a.get();
        b.get();
        pool.shutdown();

        String list = mvc.perform(get("/api/homework?openOnly=false").header(TOKEN, "test-token"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        java.util.List<String> titles = new java.util.ArrayList<>();
        for (JsonNode h : json(list)) {
            if (dueIso.equals(h.path("dueDate").asText())) {
                titles.add(h.path("title").asText());
            }
        }
        assertThat(titles).hasSize(2);
        assertThat(titles).anyMatch(t -> t.startsWith("Matteprov"));
        assertThat(titles).contains("Glosförhör v.48");
    }

    @Test
    void calendarEntryCanBeMarkedOtherWithoutDateAndSyncKeepsIt() throws Exception {
        String date = LocalDate.now().plusDays(12).toString().replace("-", "");
        java.util.function.Function<String, String> feed = uid -> "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:akt-" + uid
                + "\r\nDTSTART;VALUE=DATE:" + date + "\r\nSUMMARY:Läxa: Aktivitetsdag i Västertorshallen\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
        mvc.perform(post("/api/calendar/import").header(TOKEN, "test-token").contentType("text/plain")
                .content(feed.apply("1"))).andExpect(status().isOk());
        long id = -1;
        for (JsonNode h : json(mvc.perform(get("/api/homework").header(TOKEN, "test-token"))
                .andReturn().getResponse().getContentAsString())) {
            if (h.path("title").asText().contains("Aktivitetsdag")) {
                id = h.path("id").asLong();
            }
        }
        assertThat(id).isPositive();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/homework/" + id)
                        .header(TOKEN, "test-token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"OTHER\",\"clearDueDate\":true}"))
                .andExpect(status().isOk());
        // Ny synk med nytt UID (som InfoMentor gör) – ska inte ge ny läxa eller återställa typ/datum
        mvc.perform(post("/api/calendar/import").header(TOKEN, "test-token").contentType("text/plain")
                .content(feed.apply("2"))).andExpect(status().isOk());

        int found = 0;
        for (JsonNode h : json(mvc.perform(get("/api/homework").header(TOKEN, "test-token"))
                .andReturn().getResponse().getContentAsString())) {
            if (h.path("title").asText().contains("Aktivitetsdag")) {
                found++;
                assertThat(h.path("kind").asText()).isEqualTo("OTHER");
                assertThat(h.path("dueDate").isNull()).isTrue();
            }
        }
        assertThat(found).isEqualTo(1);
        mvc.perform(post("/api/plan/homework/" + id).header(TOKEN, "test-token")).andExpect(status().isBadRequest());
        assertThat(homeworkService.summaryForPrompt()).doesNotContain("Aktivitetsdag");
    }

    @Test
    void pastHomeworkIsFlaggedAndCanBeArchived() throws Exception {
        String past = LocalDate.now().minusDays(3).toString();
        for (String body : new String[]{
                "{\"title\":\"Gammalt prov i SO\",\"dueDate\":\"" + past + "\"}",
                "{\"title\":\"Sen engelskaläxa\",\"dueDate\":\"" + past + "\"}"}) {
            String created = mvc.perform(post("/api/homework").header(TOKEN, "test-token")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            if (body.contains("prov")) {
                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/homework/" + json(created).path("id").asLong()).header(TOKEN, "test-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"TEST\"}"));
            }
        }
        String summary = homeworkService.summaryForPrompt();
        assertThat(summary).doesNotContain("Gammalt prov i SO");
        assertThat(summary).contains("Sen engelskaläxa").contains("har passerat");

        String r = mvc.perform(post("/api/homework/archive-past").header(TOKEN, "test-token"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json(r).path("archived").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(homeworkService.summaryForPrompt()).doesNotContain("Sen engelskaläxa");
    }

    @Test
    void studentLinkOnlyReachesWhatTheStudentNeeds() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/student")
                        .header(TOKEN, "test-token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Fred\",\"tone\":\"lekfull\",\"extra\":\"Gillar fotboll\"}"))
                .andExpect(status().isOk());
        assertThat(studentProfile.promptSection()).contains("Fred", "humor", "fotboll", "inga färdiga svar");

        // Planering per elev: max minuter och pluggdagar
        String plan = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/student")
                        .header(TOKEN, "test-token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxMinutesPerDay\":25,\"studyDays\":[\"monday\",\"WEDNESDAY\",\"SUNDAY\"]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json(plan).path("maxMinutesPerDay").asInt()).isEqualTo(25);
        assertThat(json(plan).path("studyDays").toString()).isEqualTo("[\"MONDAY\",\"WEDNESDAY\",\"SUNDAY\"]");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/student")
                .header(TOKEN, "test-token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"studyDays\":[]}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/plan/replan-all").header(TOKEN, "test-token")).andExpect(status().isOk());

        String link = mvc.perform(post("/api/chat/link").header(TOKEN, "test-token"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String path = json(link).path("path").asText();
        assertThat(path).startsWith("/chat.html#t=");
        String kid = path.substring(path.indexOf("t=") + 2);

        String me = mvc.perform(get("/api/chat/me").header(TOKEN, kid))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json(me).path("name").asText()).isEqualTo("Fred");
        mvc.perform(get("/api/plan/today").header(TOKEN, kid)).andExpect(status().isOk());
        mvc.perform(get("/api/homework").header(TOKEN, kid)).andExpect(status().isOk());
        mvc.perform(get("/api/devices/tab5-1/rewards").header(TOKEN, kid)).andExpect(status().isOk());
        mvc.perform(get("/api/devices/tab5-1/rewards/events").header(TOKEN, kid)).andExpect(status().isOk());
        mvc.perform(get("/api/devices/tab5-1/rewards/payouts").header(TOKEN, kid)).andExpect(status().isForbidden());
        // Testljud för uppläsningen: tillåtet (503 här eftersom ljudtjänsten inte körs i testet)
        mvc.perform(post("/api/speech/preview").header(TOKEN, kid).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"hej\"}")).andExpect(status().is(503));
        // Inte föräldradelarna
        mvc.perform(get("/api/student").header(TOKEN, kid)).andExpect(status().isForbidden());
        mvc.perform(get("/api/chat/link").header(TOKEN, kid)).andExpect(status().isForbidden());
        mvc.perform(get("/api/calendar").header(TOKEN, kid)).andExpect(status().isForbidden());
        mvc.perform(get("/api/logs").header(TOKEN, kid)).andExpect(status().isForbidden());
        mvc.perform(post("/api/devices/tab5-1/rewards/bonus").header(TOKEN, kid)
                .contentType(MediaType.APPLICATION_JSON).content("{\"points\":500}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/homework").header(TOKEN, "fel")).andExpect(status().isUnauthorized());

        // WebSocket: elevlänken och Tab5 på samma enhet samtidigt – båda får svaren
        BlockingQueue<String> tab5 = new LinkedBlockingQueue<>();
        BlockingQueue<String> phone = new LinkedBlockingQueue<>();
        StandardWebSocketClient client = new StandardWebSocketClient();
        String kidDevice = json(me).path("deviceId").asText();
        WebSocketSession a = client.execute(collect(tab5),
                "ws://localhost:" + port + "/ws/device?deviceId=" + kidDevice + "&token=test-token").get(5, TimeUnit.SECONDS);
        // Eleven ber om ett annat enhets-id – får ändå sin egen
        WebSocketSession b = client.execute(collect(phone),
                "ws://localhost:" + port + "/ws/device?deviceId=nagon-annans&token=" + kid).get(5, TimeUnit.SECONDS);
        b.sendMessage(new TextMessage("{\"type\":\"ping\"}"));
        assertThat(json(phone.poll(5, TimeUnit.SECONDS)).path("type").asText()).isEqualTo("pong");
        assertThat(json(tab5.poll(5, TimeUnit.SECONDS)).path("type").asText()).isEqualTo("pong");
        assertThat(a.isOpen()).isTrue();
        a.close();
        b.close();

        // Ny länk → den gamla slutar fungera
        mvc.perform(post("/api/chat/link").header(TOKEN, "test-token")).andExpect(status().isOk());
        mvc.perform(get("/api/chat/me").header(TOKEN, kid)).andExpect(status().isUnauthorized());
    }

    @Test
    void eachStudentOnlySeesTheirOwnHomeworkAndLogsInWithCode() throws Exception {
        String lisa = mvc.perform(post("/api/students").header(TOKEN, "test-token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Lisa\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long lisaId = json(lisa).path("id").asLong();
        String code = json(lisa).path("code").asText();
        String lisaDevice = json(lisa).path("deviceId").asText();
        assertThat(code).hasSize(5).matches("[A-Z2-9]{5}");
        assertThat(lisaDevice).isEqualTo("lisa");

        // Föräldern lägger in en läxa åt Lisa
        mvc.perform(post("/api/homework").header(TOKEN, "test-token").header("X-Pluggis-Student", String.valueOf(lisaId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Lisas glosor\"}"))
                .andExpect(status().isCreated());
        // och en åt första eleven
        mvc.perform(post("/api/homework").header(TOKEN, "test-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Första elevens matte\"}"))
                .andExpect(status().isCreated());

        String asLisa = mvc.perform(get("/api/homework?openOnly=false").header(TOKEN, code.toLowerCase()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(asLisa).contains("Lisas glosor").doesNotContain("Första elevens matte");
        String asParentLisa = mvc.perform(get("/api/homework?openOnly=false").header(TOKEN, "test-token")
                        .header("X-Pluggis-Student", String.valueOf(lisaId)))
                .andReturn().getResponse().getContentAsString();
        assertThat(asParentLisa).contains("Lisas glosor").doesNotContain("Första elevens matte");
        String asParentFirst = mvc.perform(get("/api/homework?openOnly=false").header(TOKEN, "test-token"))
                .andReturn().getResponse().getContentAsString();
        assertThat(asParentFirst).contains("Första elevens matte").doesNotContain("Lisas glosor");

        // Eleven kan inte låtsas vara någon annan med headern
        String sneaky = mvc.perform(get("/api/homework?openOnly=false").header(TOKEN, code)
                        .header("X-Pluggis-Student", String.valueOf(se.ostberg.pluggis.student.StudentContext.defaultId())))
                .andReturn().getResponse().getContentAsString();
        assertThat(sneaky).doesNotContain("Första elevens matte");

        String me = mvc.perform(get("/api/chat/me").header(TOKEN, code)).andReturn().getResponse().getContentAsString();
        assertThat(json(me).path("name").asText()).isEqualTo("Lisa");
        assertThat(json(me).path("deviceId").asText()).isEqualTo("lisa");
        mvc.perform(get("/api/students").header(TOKEN, code)).andExpect(status().isForbidden());

        // Gissningsspärr: efter 20 fel från samma adress blir det 429
        for (int i = 0; i < 20; i++) {
            mvc.perform(get("/api/homework").header(TOKEN, "XXXX" + (char) ('A' + i)).with(r -> { r.setRemoteAddr("10.9.9.9"); return r; }))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/homework").header(TOKEN, "ZZZZZ").with(r -> { r.setRemoteAddr("10.9.9.9"); return r; }))
                .andExpect(status().is(429));

        // Ny kod → den gamla slutar fungera; ta bort eleven → hennes läxor försvinner
        String renewed = mvc.perform(post("/api/students/" + lisaId + "/code").header(TOKEN, "test-token"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json(renewed).path("code").asText()).isNotEqualTo(code);
        mvc.perform(get("/api/chat/me").header(TOKEN, code)).andExpect(status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/students/" + lisaId)
                .header(TOKEN, "test-token")).andExpect(status().isNoContent());
        Integer left = jdbc.queryForObject("select count(*) from homework where student_key = ?", Integer.class, String.valueOf(lisaId));
        assertThat(left).isZero();
    }

    @Test
    void parentCanUndoPlanItemsAndResetPoints() throws Exception {
        String dev = "reset-tab";
        var item = planItems.save(new se.ostberg.pluggis.planning.PlanItem(999L, LocalDate.now(), "Läs s. 10", 10,
                se.ostberg.pluggis.planning.PlanItem.Type.READ));
        mvc.perform(post("/api/plan/items/" + item.getId() + "/done").param("deviceId", dev).header(TOKEN, "test-token"))
                .andExpect(status().isOk());
        long afterDone = json(mvc.perform(get("/api/devices/" + dev + "/rewards").header(TOKEN, "test-token"))
                .andReturn().getResponse().getContentAsString()).path("weekPoints").asLong();
        assertThat(afterDone).isGreaterThanOrEqualTo(3);

        String undone = mvc.perform(post("/api/plan/items/" + item.getId() + "/undo").param("deviceId", dev).header(TOKEN, "test-token"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json(undone).path("done").asBoolean()).isFalse();
        long afterUndo = json(mvc.perform(get("/api/devices/" + dev + "/rewards").header(TOKEN, "test-token"))
                .andReturn().getResponse().getContentAsString()).path("weekPoints").asLong();
        // Momentets 3 poäng bort – och dagen-klar-bonusen (5) om den delades ut
        assertThat(afterUndo).isIn(afterDone - 3, afterDone - 3 - 5);

        // Bonus, ta bort en händelse, nollställ veckan
        mvc.perform(post("/api/devices/" + dev + "/rewards/bonus").header(TOKEN, "test-token")
                .contentType(MediaType.APPLICATION_JSON).content("{\"points\":20,\"reason\":\"Test\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/devices/" + dev + "/rewards/bonus").header(TOKEN, "test-token")
                .contentType(MediaType.APPLICATION_JSON).content("{\"points\":5,\"reason\":\"Test 2\"}")).andExpect(status().isOk());
        JsonNode events = json(mvc.perform(get("/api/devices/" + dev + "/rewards/events").header(TOKEN, "test-token"))
                .andReturn().getResponse().getContentAsString());
        long bonusId = events.get(0).path("id").asLong();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/devices/" + dev + "/rewards/events/" + bonusId)
                .header(TOKEN, "test-token")).andExpect(status().isNoContent());
        String reset = mvc.perform(post("/api/devices/" + dev + "/rewards/reset").header(TOKEN, "test-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"scope\":\"week\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json(reset).path("week").path("weekPoints").asLong()).isZero();

        // Elevkoden når inte ångra/nollställ
        String kid = json(mvc.perform(post("/api/students").header(TOKEN, "test-token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Testis\"}")).andReturn().getResponse().getContentAsString()).path("code").asText();
        mvc.perform(post("/api/plan/items/" + item.getId() + "/undo").header(TOKEN, kid)).andExpect(status().isForbidden());
        mvc.perform(post("/api/devices/" + dev + "/rewards/reset").header(TOKEN, kid)).andExpect(status().isForbidden());
    }

    private static TextWebSocketHandler collect(BlockingQueue<String> into) {
        return new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                into.add(message.getPayload());
            }
        };
    }

    @Test
    void wordAndTextMaterialIsSavedOnHomeworkEvenWhenClaudeIsDown() throws Exception {
        String created = mvc.perform(post("/api/homework").header(TOKEN, "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"SO\",\"title\":\"Prov vikingatiden\",\"dueDate\":\""
                                + LocalDate.now().plusDays(8) + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = json(created).path("id").asLong();

        byte[] word = se.ostberg.pluggis.support.UploadedDocumentTest.docx(
                "<w:document><w:body><w:p><w:r><w:t>Vikingatiden s. 80–95: handel, resor, gudar</w:t></w:r></w:p></w:body></w:document>");
        var file = new org.springframework.mock.web.MockMultipartFile("file", "vikingar.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", word);
        String res = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/homework/upload").file(file).param("homeworkId", String.valueOf(id))
                        .header(TOKEN, "test-token"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json(res).path("note").asText()).contains("sparades");

        String list = mvc.perform(get("/api/homework/" + id + "/material").header(TOKEN, "test-token"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json(list).get(0).path("text").asText()).contains("Vikingatiden");
        assertThat(json(list).get(0).path("kind").asText()).isEqualTo("WORD");

        // Flera filer i samma uppladdning → en materialrad per fil, med sidnummer
        byte[] word2 = se.ostberg.pluggis.support.UploadedDocumentTest.docx(
                "<w:document><w:body><w:p><w:r><w:t>Asatron: Oden, Tor och Frej</w:t></w:r></w:p></w:body></w:document>");
        var f1 = new org.springframework.mock.web.MockMultipartFile("file", "s1.docx", "application/octet-stream", word);
        var f2 = new org.springframework.mock.web.MockMultipartFile("file", "s2.docx", "application/octet-stream", word2);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/homework/upload").file(f1).file(f2).param("homeworkId", String.valueOf(id))
                        .header(TOKEN, "test-token"))
                .andExpect(status().isOk());
        JsonNode all = json(mvc.perform(get("/api/homework/" + id + "/material").header(TOKEN, "test-token"))
                .andReturn().getResponse().getContentAsString());
        assertThat(all).hasSize(3);
        assertThat(all.get(1).path("page").asInt()).isEqualTo(1);
        assertThat(all.get(2).path("page").asInt()).isEqualTo(2);
        assertThat(all.get(2).path("text").asText()).contains("Asatron");
        assertThat(all.get(2).path("hasImage").asBoolean()).isFalse();
        assertThat(all.get(2).has("image")).isFalse();
        mvc.perform(get("/api/homework/material/" + all.get(2).path("id").asLong() + "/image").header(TOKEN, "test-token"))
                .andExpect(status().isNotFound());

        // Ny läxa från fil kräver Claude – ska ge ett begripligt fel, inte 500
        var png = new org.springframework.mock.web.MockMultipartFile("file", "skarm.png", "image/png", new byte[]{1, 2, 3});
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/homework/upload").file(png).header(TOKEN, "test-token"))
                .andExpect(status().is(502));
        var mp3 = new org.springframework.mock.web.MockMultipartFile("file", "x.mp3", "audio/mpeg", new byte[]{1});
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/homework/upload").file(mp3).header(TOKEN, "test-token"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void voiceSettingsAreStoredEvenWithoutSpeechService() throws Exception {
        String saved = mvc.perform(put("/api/speech").header(TOKEN, "test-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"voice\":\"sv_SE-lisa-medium\",\"speed\":1.15}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode s = json(saved);
        assertThat(s.path("enabled").asBoolean()).isFalse();
        assertThat(s.path("voice").asText()).isEqualTo("sv_SE-lisa-medium");
        assertThat(s.path("speed").asDouble()).isEqualTo(1.15);
        mvc.perform(post("/api/speech/preview").header(TOKEN, "test-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void backendLogIsAvailableToTheWebUi() throws Exception {
        org.slf4j.LoggerFactory.getLogger("se.ostberg.pluggis.test").warn("Loggtest från smoke-testet");
        String body = mvc.perform(get("/api/logs?level=WARN&q=loggtest").header(TOKEN, "test-token"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode logs = json(body);
        assertThat(logs.path("lines").size()).isGreaterThanOrEqualTo(1);
        assertThat(logs.path("lines").get(0).path("message").asText()).contains("Loggtest");
        mvc.perform(get("/api/logs")).andExpect(status().isUnauthorized());
    }

    @Test
    void coachAnswersWithFallbackWhenClaudeIsUnreachable() throws Exception {
        String reply = mvc.perform(post("/api/devices/tab5-2/coach").header(TOKEN, "test-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"hej\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(json(reply).path("reply").asText()).isNotBlank();
    }

    @Test
    void deviceWebSocketHandlesHelloButtonsAndRejectsBadToken() throws Exception {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        var handler = new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                received.add(message.getPayload());
            }
        };
        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession ws = client.execute(handler,
                "ws://localhost:" + port + "/ws/device?deviceId=tab5-ws&token=test-token").get(5, TimeUnit.SECONDS);

        ws.sendMessage(new TextMessage("{\"type\":\"hello\",\"audio\":false}"));
        assertThat(json(received.poll(5, TimeUnit.SECONDS)).path("type").asText()).isEqualTo("hello_ack");

        ws.sendMessage(new TextMessage("{\"type\":\"button\",\"button\":\"photo\",\"gesture\":\"long\"}"));
        JsonNode photo = json(received.poll(5, TimeUnit.SECONDS));
        assertThat(photo.path("type").asText()).isEqualTo("take_photo");
        assertThat(photo.path("uploadPath").asText()).isEqualTo("/api/flashcards/photo");

        // Claude är inte nåbar – coachen ska ändå svara (med reservsvaret)
        ws.sendMessage(new TextMessage("{\"type\":\"button\",\"button\":\"main\",\"gesture\":\"click\"}"));
        assertThat(json(received.poll(5, TimeUnit.SECONDS)).path("type").asText()).isEqualTo("thinking");
        JsonNode say = json(received.poll(15, TimeUnit.SECONDS));
        assertThat(say.path("type").asText()).isEqualTo("say");
        assertThat(say.path("text").asText()).isNotBlank();
        ws.close();

        WebSocketSession bad = client.execute(new TextWebSocketHandler(),
                "ws://localhost:" + port + "/ws/device?deviceId=x&token=fel").get(5, TimeUnit.SECONDS);
        for (int i = 0; i < 50 && bad.isOpen(); i++) {
            Thread.sleep(100);
        }
        assertThat(bad.isOpen()).isFalse();
    }
}
