package se.ostberg.pluggis.games;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import se.ostberg.pluggis.claude.ClaudeClient;
import se.ostberg.pluggis.config.PluggisProperties;
import se.ostberg.pluggis.homework.Homework;
import se.ostberg.pluggis.homework.HomeworkService;
import se.ostberg.pluggis.homework.MaterialService;
import se.ostberg.pluggis.rewards.RewardsService;
import se.ostberg.pluggis.support.JsonExtract;
import se.ostberg.pluggis.support.Prompts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Snabba övningar i Öva: "Rätt ordning" (Claude gör ordningarna av materialet) och poäng när eleven klarat
 * en övning. "Matcha par" spelas helt i appen med elevens kortlekar.
 */
@RestController
@RequestMapping("/api/games")
public class GamesController {

    private static final Logger log = LoggerFactory.getLogger(GamesController.class);

    private final ClaudeClient claude;
    private final Prompts prompts;
    private final PluggisProperties props;
    private final HomeworkService homework;
    private final MaterialService materials;
    private final RewardsService rewards;
    private final ObjectMapper mapper;

    public GamesController(ClaudeClient claude, Prompts prompts, PluggisProperties props, HomeworkService homework,
                           MaterialService materials, RewardsService rewards, ObjectMapper mapper) {
        this.claude = claude;
        this.prompts = prompts;
        this.props = props;
        this.homework = homework;
        this.materials = materials;
        this.rewards = rewards;
        this.mapper = mapper;
    }

    public record OrderRequest(Long homeworkId, String focus) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Sequence(String title, String prompt, List<String> steps, String why) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Generated(List<Sequence> sequences, String note) {
    }

    public record OrderGame(List<Sequence> sequences, String note) {
    }

    public record DoneRequest(String kind, String what) {
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private se.ostberg.pluggis.support.Variety variety;

    @PostMapping("/order")
    public ResponseEntity<?> order(@RequestBody OrderRequest body) {
        Map<String, Object> task = new LinkedHashMap<>();
        if (body.homeworkId() != null) {
            Homework h = homework.get(body.homeworkId()).orElse(null);
            if (h == null) {
                return ResponseEntity.notFound().build();
            }
            task.put("title", h.getTitle());
            task.put("subject", h.getSubject());
            task.put("content", h.getDescription());
            task.put("steps", h.getSteps());
            String mat = materials.allText(h.getId());
            task.put("material", mat.length() > 15000 ? mat.substring(0, 15000) : mat);
        }
        boolean focused = body.focus() != null && !body.focus().isBlank();
        String vkey = focused ? "order|" + body.focus().trim() : "order";
        if (focused) {
            task.put("focus", body.focus().trim());
        }
        if (variety != null) {
            List<String> avoid = variety.recent(vkey, body.homeworkId());
            if (!avoid.isEmpty()) {
                task.put("avoid", avoid);
            }
            task.put("variation", variety.focusHint());
        }
        try {
            ObjectNode req = claude.newRequest(props.anthropic().flashcards(), prompts.order());
            ObjectNode msg = req.putArray("messages").addObject();
            msg.put("role", "user");
            msg.put("content", mapper.writeValueAsString(task));
            Generated g = JsonExtract.parse(claude.create(req, "order").text(), mapper, Generated.class);
            List<Sequence> ok = clean(g);
            if (ok.isEmpty()) {
                return ResponseEntity.unprocessableEntity().body(Map.of("error",
                        g.note() != null ? g.note() : "Hittade inget i materialet som har en tydlig ordning"));
            }
            if (variety != null) {
                variety.remember(vkey, body.homeworkId(), ok.stream().map(Sequence::title).toList());
            }
            return ResponseEntity.ok(new OrderGame(ok, g.note()));
        } catch (Exception e) {
            log.warn("Rätt ordning kunde inte skapas: {}", e.toString());
            String limit = se.ostberg.pluggis.claude.SubscriptionLimit.userMessage(e);
            return ResponseEntity.status(503).body(Map.of("error", limit != null ? limit : "Pluggis kunde inte göra övningen just nu – prova igen om en stund"));
        }
    }

    /** Ordningar med 3–7 olika, icke-tomma steg. */
    static List<Sequence> clean(Generated g) {
        List<Sequence> out = new ArrayList<>();
        if (g == null || g.sequences() == null) {
            return out;
        }
        for (Sequence s : g.sequences()) {
            if (s == null || s.steps() == null) {
                continue;
            }
            LinkedHashSet<String> steps = new LinkedHashSet<>();
            s.steps().stream().filter(x -> x != null && !x.isBlank()).map(String::trim).forEach(steps::add);
            if (steps.size() < 3 || steps.size() != s.steps().size() || steps.size() > 7) {
                continue;
            }
            out.add(new Sequence(s.title() == null || s.title().isBlank() ? "Rätt ordning" : s.title().trim(),
                    s.prompt() == null || s.prompt().isBlank() ? "Tryck i rätt ordning" : s.prompt().trim(),
                    new ArrayList<>(steps), s.why()));
        }
        return out;
    }

    /** Eleven klarade en övning: {@code kind} = match eller order. */
    @PostMapping("/done")
    public ResponseEntity<?> done(@RequestBody DoneRequest body, @RequestParam(required = false) String deviceId) {
        if (body.kind() == null || !List.of("match", "order").contains(body.kind())) {
            return ResponseEntity.badRequest().body(Map.of("error", "okänd övning"));
        }
        rewards.onGame(deviceId, body.kind(), body.what());
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
