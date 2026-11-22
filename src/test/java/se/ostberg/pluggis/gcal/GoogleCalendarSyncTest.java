package se.ostberg.pluggis.gcal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import se.ostberg.pluggis.planning.PlanItem;
import se.ostberg.pluggis.planning.PlanItemRepository;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Google Kalender mot en låtsas-Google: inloggning med signerad JWT, och skapa/ändra/ta bort pass. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:gcaltest;DB_CLOSE_DELAY=-1",
        "pluggis.device-token=test-token",
        "pluggis.anthropic.api-key=test",
        "pluggis.anthropic.base-url=http://127.0.0.1:9",
        "pluggis.speech.base-url=",
        "pluggis.calendar.sync-cron=-",
        "pluggis.google.sync-cron=-",
        "pluggis.planning.replan-cron=-"
})
@AutoConfigureMockMvc
class GoogleCalendarSyncTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final KeyPair KEYS;
    private static final HttpServer GOOGLE;
    static final Map<String, ObjectNode> EVENTS = new ConcurrentHashMap<>();
    static final AtomicInteger IDS = new AtomicInteger();
    static volatile boolean jwtVerified;

    static {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KEYS = g.generateKeyPair();
            GOOGLE = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            GOOGLE.createContext("/token", GoogleCalendarSyncTest::token);
            GOOGLE.createContext("/calendar/v3/calendars/", GoogleCalendarSyncTest::events);
            GOOGLE.start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stop() {
        GOOGLE.stop(0);
    }

    @DynamicPropertySource
    static void google(DynamicPropertyRegistry r) {
        String base = "http://127.0.0.1:" + GOOGLE.getAddress().getPort();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(KEYS.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        ObjectNode sa = M.createObjectNode();
        sa.put("type", "service_account");
        sa.put("client_email", "pluggis@test.iam.gserviceaccount.com");
        sa.put("private_key", pem);
        sa.put("token_uri", base + "/token");
        r.add("pluggis.google.service-account-json", sa::toString);
        r.add("pluggis.google.api-base", () -> base + "/calendar/v3");
    }

    private static void token(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String jwt = URLDecoder.decode(body.replaceAll(".*assertion=([^&]+).*", "$1"), StandardCharsets.UTF_8);
        String[] parts = jwt.split("\\.");
        try {
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initVerify(KEYS.getPublic());
            s.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
            JsonNode claims = M.readTree(Base64.getUrlDecoder().decode(parts[1]));
            jwtVerified = s.verify(Base64.getUrlDecoder().decode(parts[2]))
                    && claims.path("iss").asText().equals("pluggis@test.iam.gserviceaccount.com")
                    && claims.path("scope").asText().contains("calendar.events");
        } catch (Exception e) {
            jwtVerified = false;
        }
        send(ex, 200, "{\"access_token\":\"tok\",\"expires_in\":3600}");
    }

    private static void events(HttpExchange ex) throws IOException {
        if (!"Bearer tok".equals(ex.getRequestHeaders().getFirst("Authorization"))) {
            send(ex, 401, "{}");
            return;
        }
        String path = ex.getRequestURI().getPath(); // /calendar/v3/calendars/{cal}/events[/{id}]
        String[] p = path.split("/");
        String id = p.length > 6 ? p[6] : null;
        switch (ex.getRequestMethod()) {
            case "GET" -> {
                String query = URLDecoder.decode(String.valueOf(ex.getRequestURI().getRawQuery()), StandardCharsets.UTF_8);
                String student = query.replaceAll(".*privateExtendedProperty=pluggisStudent=([^&]+).*", "$1");
                ArrayNode items = M.createArrayNode();
                EVENTS.values().stream()
                        .filter(e -> e.path("extendedProperties").path("private").path("pluggisStudent").asText().equals(student))
                        .forEach(items::add);
                send(ex, 200, M.createObjectNode().set("items", items).toString());
            }
            case "POST" -> {
                ObjectNode ev = (ObjectNode) M.readTree(ex.getRequestBody());
                String newId = "ev" + IDS.incrementAndGet();
                ev.put("id", newId);
                ev.put("htmlLink", "https://calendar.google.com/event?eid=" + newId);
                EVENTS.put(newId, ev);
                send(ex, 200, ev.toString());
            }
            case "PUT" -> {
                ObjectNode ev = (ObjectNode) M.readTree(ex.getRequestBody());
                ev.put("id", id);
                EVENTS.put(id, ev);
                send(ex, 200, ev.toString());
            }
            case "DELETE" -> {
                EVENTS.remove(id);
                send(ex, 204, null);
            }
            default -> send(ex, 405, "{}");
        }
    }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        if (body == null) {
            ex.sendResponseHeaders(code, -1);
        } else {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(code, b.length);
            ex.getResponseBody().write(b);
        }
        ex.close();
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    PlanItemRepository items;

    private JsonNode call(org.springframework.test.web.servlet.RequestBuilder rb) throws Exception {
        return M.readTree(mvc.perform(rb).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void planBecomesOneSessionPerDayInGoogleCalendar() throws Exception {
        JsonNode status0 = call(get("/api/google-calendar").header("X-Pluggis-Token", "test-token"));
        assertThat(status0.path("enabled").asBoolean()).isTrue();
        assertThat(status0.path("serviceAccountEmail").asText()).isEqualTo("pluggis@test.iam.gserviceaccount.com");

        // Pluggtid och en läxa med plan över två dagar
        mvc.perform(put("/api/student").header("X-Pluggis-Token", "test-token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"studyTime\":\"16:15\",\"studyCue\":\"efter mellanmålet\",\"studyDays\":[\"MONDAY\",\"TUESDAY\",\"WEDNESDAY\",\"THURSDAY\",\"FRIDAY\",\"SATURDAY\",\"SUNDAY\"]}"))
                .andExpect(status().isOk());
        JsonNode hw = M.readTree(mvc.perform(post("/api/homework").header("X-Pluggis-Token", "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"NO\",\"title\":\"NO-prov fotosyntes\",\"dueDate\":\"" + LocalDate.now().plusDays(5) + "\"}"))
                .andReturn().getResponse().getContentAsString());
        long hid = hw.path("id").asLong();
        LocalDate d1 = LocalDate.now().plusDays(1);
        LocalDate d2 = LocalDate.now().plusDays(2);
        PlanItem a = items.save(new PlanItem(hid, d1, "Läs rubrikerna s. 40–48", 5, PlanItem.Type.READ));
        items.save(new PlanItem(hid, d1, "Skriv 3 nyckelord", 15, PlanItem.Type.WRITE));
        PlanItem c = items.save(new PlanItem(hid, d2, "Förhör dig på begreppen", 15, PlanItem.Type.REVIEW));

        JsonNode linked = call(put("/api/google-calendar").header("X-Pluggis-Token", "test-token")
                .contentType(MediaType.APPLICATION_JSON).content("{\"calendarId\":\"fred@group.calendar.google.com\"}"));
        assertThat(jwtVerified).isTrue();
        assertThat(linked.path("result").path("created").asInt()).isEqualTo(2);
        ObjectNode day1 = EVENTS.values().stream()
                .filter(e -> e.path("extendedProperties").path("private").path("pluggisDay").asText().equals(d1.toString()))
                .findFirst().orElseThrow();
        assertThat(day1.path("summary").asText()).isEqualTo("📚 Plugg 20 min: NO-prov fotosyntes");
        assertThat(day1.path("description").asText()).contains("🧪 NO-prov fotosyntes – 20 min").contains("☐ Läs rubrikerna s. 40–48 (5 min)")
                .contains("När: efter mellanmålet");
        assertThat(day1.path("start").path("dateTime").asText()).contains("T16:15");
        assertThat(day1.path("end").path("dateTime").asText()).contains("T16:35");
        assertThat(day1.path("reminders").path("overrides").get(0).path("minutes").asInt()).isZero();

        JsonNode st = call(get("/api/google-calendar").header("X-Pluggis-Token", "test-token"));
        assertThat(st.path("upcoming")).hasSize(2);
        assertThat(st.path("upcoming").get(0).path("link").asText()).startsWith("https://calendar.google.com/event");
        assertThat(PlanCalendarSync.withAuthUser("https://www.google.com/calendar/event?eid=x", "fred@gmail.com"))
                .isEqualTo("https://www.google.com/calendar/event?eid=x&authuser=fred%40gmail.com");

        // Inget ändrat → inget att göra
        JsonNode again = call(post("/api/google-calendar/sync").header("X-Pluggis-Token", "test-token"));
        assertThat(again.path("result").path("updated").asInt()).isZero();
        assertThat(again.path("result").path("created").asInt()).isZero();

        // Bocka av ett moment → passet uppdateras
        mvc.perform(post("/api/plan/items/" + a.getId() + "/done").param("deviceId", "tab5-1").header("X-Pluggis-Token", "test-token"))
                .andExpect(status().isOk());
        JsonNode afterDone = call(post("/api/google-calendar/sync").header("X-Pluggis-Token", "test-token"));
        assertThat(afterDone.path("result").path("updated").asInt()).isEqualTo(1);
        assertThat(EVENTS.values().stream().anyMatch(e -> e.path("description").asText().contains("✅ Läs rubrikerna"))).isTrue();

        // Framsteg: 5 av 35 minuter klart
        JsonNode prog = call(get("/api/plan/progress").header("X-Pluggis-Token", "test-token"));
        JsonNode p = null;
        for (JsonNode x : prog) {
            if (x.path("homeworkId").asLong() == hid) {
                p = x;
            }
        }
        assertThat(p).isNotNull();
        assertThat(p.path("percent").asInt()).isEqualTo(14);
        assertThat(p.path("daysLeft").asInt()).isEqualTo(5);

        // Dag 2 försvinner ur planen → passet tas bort
        items.deleteById(c.getId());
        JsonNode afterDelete = call(post("/api/google-calendar/sync").header("X-Pluggis-Token", "test-token"));
        assertThat(afterDelete.path("result").path("deleted").asInt()).isEqualTo(1);
        assertThat(EVENTS).hasSize(1);

        // Koppla bort → kommande pass bort
        call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/google-calendar")
                .header("X-Pluggis-Token", "test-token"));
        assertThat(EVENTS).isEmpty();
    }
}
