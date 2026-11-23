package se.ostberg.pluggis.usage;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import se.ostberg.pluggis.claude.ClaudeClient;
import se.ostberg.pluggis.config.PluggisProperties;
import se.ostberg.pluggis.student.Student;
import se.ostberg.pluggis.student.StudentService;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Kostnadsöversikt för föräldern (elevkoden når inte /api/costs). */
@RestController
@RequestMapping("/api/costs")
public class CostController {

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("coach", "Coachen (röst och chatt)"),
            Map.entry("vision", "Foto av läxa"),
            Map.entry("material", "Material (bilder, dokument)"),
            Map.entry("planning", "Planering"),
            Map.entry("intake-text", "Läxa från text"),
            Map.entry("flashcards", "Flashkort"),
            Map.entry("flashcards-vision", "Flashkort från bild"),
            Map.entry("flashcards-document", "Flashkort från dokument"),
            Map.entry("flashcards-fix", "Flashkort, rättning"),
            Map.entry("quiz", "Snabbquiz och lucktext"),
            Map.entry("explain-start", "Förklara för Pluggis"),
            Map.entry("explain-answer", "Förklara för Pluggis, svar"),
            Map.entry("exam", "Testprov"),
            Map.entry("exam-grade", "Testprov, rättning"));

    private final UsageDayRepository repo;
    private final CostProperties costs;
    private final PluggisProperties props;
    private final StudentService students;
    private final ClaudeClient claude;

    public CostController(UsageDayRepository repo, CostProperties costs, PluggisProperties props,
                          StudentService students, ClaudeClient claude) {
        this.repo = repo;
        this.costs = costs;
        this.props = props;
        this.students = students;
        this.claude = claude;
    }

    @GetMapping
    public Map<String, Object> overview(@RequestParam(defaultValue = "30") int days) {
        int span = Math.max(7, Math.min(days, 180));
        LocalDate today = LocalDate.now(props.zone());
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate from = today.minusDays(span - 1L).isBefore(monthStart) ? today.minusDays(span - 1L) : monthStart;
        List<UsageDay> rows = repo.findByDayGreaterThanEqualOrderByDayAsc(from);
        double rate = costs.rate();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("usdSek", rate);
        out.put("backend", claude.backend());
        out.put("today", totals(rows.stream().filter(r -> r.getDay().equals(today)).toList(), rate));
        out.put("month", totals(rows.stream().filter(r -> !r.getDay().isBefore(monthStart)).toList(), rate));
        out.put("monthName", today.getMonth().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.of("sv", "SE")));

        // Per dag (hela perioden, även dagar utan anrop)
        TreeMap<LocalDate, double[]> perDay = new TreeMap<>();
        for (LocalDate d = today.minusDays(span - 1L); !d.isAfter(today); d = d.plusDays(1)) {
            perDay.put(d, new double[2]);
        }
        for (UsageDay r : rows) {
            double[] v = perDay.get(r.getDay());
            if (v != null) {
                v["subscription".equals(r.getBackend()) ? 1 : 0] += r.getCostUsd() * rate;
            }
        }
        List<Map<String, Object>> dayList = new ArrayList<>();
        perDay.forEach((d, v) -> dayList.add(Map.of("day", d.toString(), "sek", round(v[0]), "subscriptionSek", round(v[1]))));
        out.put("days", dayList);

        List<UsageDay> month = rows.stream().filter(r -> !r.getDay().isBefore(monthStart)).toList();

        // Per jobb denna månad
        Map<String, List<UsageDay>> byPurpose = new TreeMap<>();
        month.stream().filter(r -> !"azure".equals(r.getBackend()))
                .forEach(r -> byPurpose.computeIfAbsent(r.getPurpose(), k -> new ArrayList<>()).add(r));
        List<Map<String, Object>> purposes = new ArrayList<>();
        byPurpose.forEach((p, list) -> {
            Map<String, Object> m = totals(list, rate);
            m.put("purpose", p);
            m.put("label", LABELS.getOrDefault(p, p));
            m.put("models", list.stream().map(UsageDay::getModel).distinct().sorted().toList());
            purposes.add(m);
        });
        purposes.sort((a, b) -> Double.compare((double) b.get("totalSek"), (double) a.get("totalSek")));
        out.put("purposes", purposes);

        // Per elev denna månad
        Map<Long, String> names = new LinkedHashMap<>();
        for (Student s : students.all()) {
            names.put(s.getId(), s.getName().isBlank() ? "Elev " + s.getId() : s.getName());
        }
        Map<Long, List<UsageDay>> byStudent = new LinkedHashMap<>();
        month.forEach(r -> byStudent.computeIfAbsent(r.getStudentId(), k -> new ArrayList<>()).add(r));
        List<Map<String, Object>> perStudent = new ArrayList<>();
        byStudent.forEach((id, list) -> {
            Map<String, Object> m = totals(list, rate);
            m.put("name", names.getOrDefault(id, "Okänd"));
            perStudent.add(m);
        });
        out.put("students", perStudent);

        long chars = month.stream().filter(r -> "azure".equals(r.getBackend())).mapToLong(UsageDay::getCharacters).sum();
        out.put("azure", Map.of("characters", chars, "free", costs.freeChars()));

        List<Map<String, Object>> prices = new ArrayList<>();
        costs.allPrices().forEach((model, p) -> prices.add(Map.of("model", model, "input", p.input(), "output", p.output(),
                "cacheRead", p.cacheRead(), "cacheWrite", p.cacheWrite())));
        out.put("prices", prices);
        return out;
    }

    private static Map<String, Object> totals(List<UsageDay> rows, double rate) {
        double api = 0;
        double sub = 0;
        long calls = 0;
        long in = 0;
        long outTok = 0;
        for (UsageDay r : rows) {
            if ("azure".equals(r.getBackend())) {
                continue;
            }
            calls += r.getCalls();
            in += r.getInputTokens() + r.getCacheReadTokens() + r.getCacheWriteTokens();
            outTok += r.getOutputTokens();
            if ("subscription".equals(r.getBackend())) {
                sub += r.getCostUsd();
            } else {
                api += r.getCostUsd();
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sek", round(api * rate));
        m.put("subscriptionSek", round(sub * rate));
        m.put("totalSek", round((api + sub) * rate));
        m.put("usd", Math.round(api * 10000) / 10000d);
        m.put("calls", calls);
        m.put("inputTokens", in);
        m.put("outputTokens", outTok);
        return m;
    }

    private static double round(double sek) {
        return Math.round(sek * 100) / 100d;
    }
}
