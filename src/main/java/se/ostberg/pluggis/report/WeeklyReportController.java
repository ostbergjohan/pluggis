package se.ostberg.pluggis.report;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import se.ostberg.pluggis.config.PluggisProperties;
import se.ostberg.pluggis.homework.Homework;
import se.ostberg.pluggis.homework.HomeworkService;
import se.ostberg.pluggis.planning.PlanItem;
import se.ostberg.pluggis.planning.PlanItemRepository;
import se.ostberg.pluggis.planning.PlanningService;
import se.ostberg.pluggis.rewards.RewardsService;
import se.ostberg.pluggis.stats.ReviewLog;
import se.ostberg.pluggis.stats.ReviewLogRepository;
import se.ostberg.pluggis.stats.StatsService;
import se.ostberg.pluggis.student.StudentService;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Veckorapporten i föräldravyn: de senaste sju dagarna (pluggtid, planen, övningar, poäng, det som ofta blev
 * fel) och de kommande sju (prov och läxor, planerad tid). Bara för föräldern.
 */
@RestController
@RequestMapping("/api/report")
public class WeeklyReportController {

    private final StatsService stats;
    private final StudentService students;
    private final PlanItemRepository planItems;
    private final PlanningService planning;
    private final HomeworkService homework;
    private final RewardsService rewards;
    private final ReviewLogRepository reviews;
    private final PluggisProperties props;

    public WeeklyReportController(StatsService stats, StudentService students, PlanItemRepository planItems,
                                  PlanningService planning, HomeworkService homework, RewardsService rewards,
                                  ReviewLogRepository reviews, PluggisProperties props) {
        this.stats = stats;
        this.students = students;
        this.planItems = planItems;
        this.planning = planning;
        this.homework = homework;
        this.rewards = rewards;
        this.reviews = reviews;
        this.props = props;
    }

    /** {@code weeksAgo}: 0 = de senaste sju dagarna, 1 = sju dagar innan dess … */
    @GetMapping("/week")
    @Transactional(readOnly = true)
    public Map<String, Object> week(@RequestParam(defaultValue = "0") int weeksAgo) {
        var student = students.current();
        String device = student.getDeviceId();
        LocalDate today = LocalDate.now(props.zone());
        LocalDate to = today.minusDays(7L * Math.max(0, Math.min(weeksAgo, 52)));
        LocalDate from = to.minusDays(6);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("student", student.getName());
        m.put("from", from.toString());
        m.put("to", to.toString());

        // pluggtid per dag (statistiken räknar bakåt från idag)
        int span = (int) (today.toEpochDay() - from.toEpochDay()) + 1;
        var s = stats.stats(device, span);
        List<Map<String, Object>> days = new ArrayList<>();
        long minutes = 0, cardsReviewed = 0, cardsCorrect = 0, points = 0;
        int active = 0;
        for (var d : s.perDay()) {
            if (d.day().isBefore(from) || d.day().isAfter(to)) {
                continue;
            }
            days.add(Map.of("day", d.day().toString(), "minutes", d.minutes()));
            minutes += d.minutes();
            cardsReviewed += d.cardsReviewed();
            cardsCorrect += d.cardsCorrect();
            points += d.points();
            if (d.minutes() > 0 || d.cardsReviewed() > 0 || d.planItemsDone() > 0) {
                active++;
            }
        }
        m.put("perDay", days);
        m.put("minutes", minutes);
        m.put("activeDays", active);
        m.put("cards", Map.of("reviewed", cardsReviewed, "correct", cardsCorrect));
        m.put("points", points);

        // planen: hur mycket som var planerat och hur mycket som blev gjort
        List<PlanItem> planned = planItems.findByPlanDateBetweenOrderByPlanDateAscIdAsc(from, to);
        long plannedMin = planned.stream().mapToLong(PlanItem::getMinutes).sum();
        long doneMin = planned.stream().filter(PlanItem::isDone).mapToLong(PlanItem::getMinutes).sum();
        m.put("plan", Map.of("items", planned.size(), "done", planned.stream().filter(PlanItem::isDone).count(),
                "minutes", plannedMin, "doneMinutes", doneMin));
        List<String> missed = planned.stream().filter(p -> !p.isDone() && p.getPlanDate().isBefore(today))
                .map(PlanItem::getTask).limit(8).toList();
        m.put("missed", missed);

        // vad som övats (pass och övningar)
        m.put("practised", s.recent().stream()
                .filter(r -> !r.startedAt().atZone(props.zone()).toLocalDate().isBefore(from)
                        && !r.startedAt().atZone(props.zone()).toLocalDate().isAfter(to))
                .map(r -> Map.of("task", r.task(), "minutes", r.minutes(), "at", r.startedAt().toString()))
                .limit(12).toList());

        // svagt: kort som ofta blev fel (fler fel än rätt)
        Map<String, int[]> byFront = new LinkedHashMap<>();
        Map<String, String> backs = new LinkedHashMap<>();
        for (ReviewLog r : reviews.findByDeviceIdAndReviewedOnGreaterThanEqual(device, from)) {
            if (r.getReviewedOn().isAfter(to) || r.getFront() == null || r.getFront().isBlank()) {
                continue;
            }
            int[] c = byFront.computeIfAbsent(r.getFront(), k -> new int[2]);
            c[r.isCorrect() ? 0 : 1]++;
            if (r.getBack() != null) {
                backs.put(r.getFront(), r.getBack());
            }
        }
        m.put("weak", byFront.entrySet().stream()
                .filter(e -> e.getValue()[1] > 0 && e.getValue()[1] >= e.getValue()[0])
                .sorted(Comparator.comparingInt((Map.Entry<String, int[]> e) -> e.getValue()[0] - e.getValue()[1]))
                .limit(10)
                .map(e -> {
                    Map<String, Object> w = new LinkedHashMap<>();
                    w.put("front", e.getKey());
                    w.put("back", backs.get(e.getKey()));
                    w.put("wrong", e.getValue()[1]);
                    w.put("right", e.getValue()[0]);
                    return w;
                }).toList());

        // nästa vecka
        List<Map<String, Object>> coming = new ArrayList<>();
        for (Homework h : homework.open()) {
            if (h.getKind() == Homework.Kind.OTHER || h.getDueDate() == null) {
                continue;
            }
            if (!h.getDueDate().isBefore(today) && !h.getDueDate().isAfter(today.plusDays(7))) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("title", h.getTitle());
                c.put("subject", h.getSubject());
                c.put("kind", h.getKind().name());
                c.put("dueDate", h.getDueDate().toString());
                coming.add(c);
            }
        }
        coming.sort(Comparator.comparing(c -> c.get("dueDate").toString()));
        m.put("upcoming", coming);
        List<PlanItem> next = planning.upcoming(7);
        m.put("nextWeek", Map.of("items", next.stream().filter(p -> !p.isDone()).count(),
                "minutes", next.stream().filter(p -> !p.isDone()).mapToLong(PlanItem::getMinutes).sum()));

        // poäng och pengar (gäller innevarande vecka)
        if (weeksAgo == 0 && student.isPointsEnabled()) {
            var w = rewards.week(device);
            m.put("reward", Map.of("weekPoints", w.weekPoints(), "earnedSek", w.earnedSek(), "unpaidSek", w.unpaidSek(),
                    "moneyEnabled", w.moneyEnabled(), "streakDays", w.streakDays()));
        }
        return m;
    }
}
