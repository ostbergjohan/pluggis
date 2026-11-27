package se.ostberg.pluggis.claude;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Välj om Claude ska gå via API-nyckeln, abonnemanget (Claude Code) eller hybrid – bara föräldern. */
@RestController
@RequestMapping("/api/claude")
public class ClaudeBackendController {

    private final ClaudeClient claude;
    private final ClaudeCodeRunner runner;

    private final SubscriptionUsage usage;

    public ClaudeBackendController(ClaudeClient claude, ClaudeCodeRunner runner, SubscriptionUsage usage) {
        this.claude = claude;
        this.runner = runner;
        this.usage = usage;
    }

    @GetMapping
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", runner.mode());
        m.put("configuredMode", runner.configuredMode());
        m.put("apiKey", claude.hasApiKey());
        m.put("token", runner.hasToken());
        m.put("cliVersion", runner.cliVersion());
        m.put("apiPurposes", runner.apiPurposes());
        m.put("claudeCode", runner.stats());
        m.put("fallbacks", claude.fallbacks());
        m.put("problems", problems(runner.mode()));
        Map<String, Object> sub = new LinkedHashMap<>(SubscriptionLimit.snapshot());
        if (!"api".equals(runner.mode())) {
            Map<String, Object> u = usage.get(false);
            if (u != null) {
                sub.put("usage", u);
                int max = SubscriptionUsage.highest(u);
                if (max >= 80 && !Boolean.TRUE.equals(sub.get("reached"))) {
                    sub.put("near", true);
                }
            }
        }
        m.put("subscription", sub);
        return m;
    }

    public record ModeRequest(String mode) {
    }

    /**
     * Ett litet anrop via abonnemanget (Haiku, några ord) för att få veta läget för gränsen nu. Kostar en
     * mycket liten del av abonnemanget.
     */
    @PostMapping("/subscription/check")
    public ResponseEntity<Map<String, Object>> checkSubscription() {
        if ("api".equals(runner.mode()) || !runner.hasToken()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Abonnemanget används inte (läget är bara API-nyckel eller token saknas)"));
        }
        try {
            var req = claude.newRequest(claude.config().coach(), "Svara bara med ordet ok.");
            req.put("max_tokens", 5);
            var msg = req.putArray("messages").addObject();
            msg.put("role", "user");
            msg.put("content", "ok?");
            usage.get(true);
            runner.run(req);
        } catch (RuntimeException e) {
            // gränsen nådd eller annat fel: läget är uppdaterat ändå (eller felet syns i statusen)
        }
        return ResponseEntity.ok(status());
    }

    @PutMapping
    public ResponseEntity<Map<String, Object>> setMode(@RequestBody ModeRequest req) {
        String mode = req.mode() == null || req.mode().isBlank() ? null : req.mode().trim();
        if (mode != null && !ClaudeCodeRunner.MODES.contains(mode)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Okänt läge: " + mode));
        }
        List<String> problems = problems(mode == null ? runner.configuredMode() : mode);
        if (!problems.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", String.join(" ", problems)));
        }
        runner.setMode(mode);
        return ResponseEntity.ok(status());
    }

    /** Vad som saknas för att ett läge ska fungera. */
    private List<String> problems(String mode) {
        List<String> p = new java.util.ArrayList<>();
        boolean needsApi = !"claude-code".equals(mode);
        boolean needsCc = !"api".equals(mode);
        if (needsApi && !claude.hasApiKey()) {
            p.add("ANTHROPIC_API_KEY saknas i .env.");
        }
        if (needsCc && !runner.hasToken()) {
            p.add("CLAUDE_CODE_OAUTH_TOKEN saknas i .env (skapa med \"claude setup-token\").");
        }
        if (needsCc && runner.cliVersion() == null) {
            p.add("Programmet claude finns inte i containern.");
        }
        return p;
    }
}
