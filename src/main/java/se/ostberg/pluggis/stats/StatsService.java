package se.ostberg.pluggis.stats;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import se.ostberg.pluggis.config.PluggisProperties;
import se.ostberg.pluggis.flashcards.FlashcardService;
import se.ostberg.pluggis.homework.Homework;
import se.ostberg.pluggis.homework.HomeworkService;
import se.ostberg.pluggis.planning.PlanItem;
import se.ostberg.pluggis.planning.PlanItemRepository;
import se.ostberg.pluggis.rewards.PointEvent;
import se.ostberg.pluggis.rewards.PointEventRepository;
import se.ostberg.pluggis.session.StudySession;
import se.ostberg.pluggis.session.StudySessionService;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Statistik till föräldravyn: per dag och totalt, plus träffsäkerhet per kortlek. */
@Service
public class StatsService {

    public record DayStat(LocalDate day, long minutes, int sessions, int cardsReviewed, int cardsCorrect,
                          int planItemsDone, long points) {
    }

    public record DeckAccuracy(long deckId, String name, long cards, long learned, int reviewed, int correct) {
    }

    /** Ett pass eller en övning, för "Senaste" i föräldravyn. */
    public record Recent(String task, Instant startedAt, long minutes) {
    }

    public record Stats(String deviceId, int days, List<DayStat> perDay,
                        long totalMinutes, int totalSessions, int totalCardsReviewed, int totalCardsCorrect,
                        int totalPlanItemsDone, long totalPoints, int activeDays,
                        List<DeckAccuracy> decks, int openHomework, int upcomingTests, List<Recent> recent) {
    }

    private final ReviewLogRepository reviews;
    private final StudySessionService sessions;
    private final PointEventRepository points;
    private final PlanItemRepository planItems;
    private final FlashcardService flashcards;
    private final HomeworkService homework;
    private final PluggisProperties props;

    public StatsService(ReviewLogRepository reviews, StudySessionService sessions, PointEventRepository points,
                        PlanItemRepository planItems, FlashcardService flashcards, HomeworkService homework,
                        PluggisProperties props) {
        this.reviews = reviews;
        this.sessions = sessions;
        this.points = points;
        this.planItems = planItems;
        this.flashcards = flashcards;
        this.homework = homework;
        this.props = props;
    }

    @EventListener
    @Transactional
    public void onCardGraded(FlashcardService.CardGraded e) {
        Instant now = Instant.now();
        reviews.save(new ReviewLog(e.deviceId(), e.card().getId(), e.card().getDeckId(), e.correct(),
                LocalDate.now(props.zone()), now).withText(e.card().getFront(), e.card().getBack()));
    }

    @Transactional(readOnly = true)
    public Stats stats(String deviceId, int days) {
        int n = Math.max(1, Math.min(90, days));
        ZoneId zone = props.zone();
        LocalDate today = LocalDate.now(zone);
        LocalDate from = today.minusDays(n - 1L);
        Instant fromInstant = from.atStartOfDay(zone).toInstant();

        Map<LocalDate, long[]> acc = new LinkedHashMap<>(); // minutes, sessions, reviewed, correct, planDone, points
        for (int i = 0; i < n; i++) {
            acc.put(from.plusDays(i), new long[6]);
        }

        List<Recent> recent = new java.util.ArrayList<>();
        for (StudySession s : sessions.since(deviceId, fromInstant)) {
            Instant fin = s.getFinishedAt() != null ? s.getFinishedAt() : Instant.now();
            recent.add(new Recent(s.getTask(), s.getStartedAt(),
                    Math.max(0, Math.min(Duration.between(s.getStartedAt(), fin).toMinutes(), s.getPlannedMinutes()))));
            long[] a = acc.get(s.getStartedAt().atZone(zone).toLocalDate());
            if (a == null) {
                continue;
            }
            Instant end = s.getFinishedAt() != null ? s.getFinishedAt() : Instant.now();
            long minutes = Math.min(Duration.between(s.getStartedAt(), end).toMinutes(), s.getPlannedMinutes());
            a[0] += Math.max(0, minutes);
            a[1]++;
        }
        for (ReviewLog r : reviews.findByDeviceIdAndReviewedOnGreaterThanEqual(deviceId, from)) {
            long[] a = acc.get(r.getReviewedOn());
            if (a != null) {
                a[2]++;
                if (r.isCorrect()) {
                    a[3]++;
                }
            }
        }
        for (PlanItem p : planItems.findByDoneTrueAndDoneAtAfter(fromInstant)) {
            long[] a = acc.get(p.getDoneAt().atZone(zone).toLocalDate());
            if (a != null) {
                a[4]++;
            }
        }
        for (PointEvent p : points.findByDeviceIdAndAwardedOnGreaterThanEqual(deviceId, from)) {
            long[] a = acc.get(p.getAwardedOn());
            if (a != null) {
                a[5] += p.getPoints();
            }
        }

        List<DayStat> perDay = new ArrayList<>();
        acc.forEach((day, a) -> perDay.add(new DayStat(day, a[0], (int) a[1], (int) a[2], (int) a[3], (int) a[4], a[5])));

        Map<Long, int[]> perDeck = new LinkedHashMap<>();
        for (ReviewLog r : reviews.findByDeviceIdAndReviewedOnGreaterThanEqual(deviceId, from)) {
            int[] d = perDeck.computeIfAbsent(r.getDeckId(), k -> new int[2]);
            d[0]++;
            if (r.isCorrect()) {
                d[1]++;
            }
        }
        List<DeckAccuracy> decks = flashcards.decksWithStats().stream()
                .map(ds -> {
                    int[] d = perDeck.getOrDefault(ds.deck().getId(), new int[2]);
                    return new DeckAccuracy(ds.deck().getId(), ds.deck().getName(), ds.cards(), ds.learned(), d[0], d[1]);
                })
                .toList();

        List<Homework> open = homework.openSchoolwork();
        int upcomingTests = (int) open.stream()
                .filter(h -> h.getKind() == Homework.Kind.TEST && h.getDueDate() != null && !h.getDueDate().isBefore(today))
                .count();

        return new Stats(deviceId, n, perDay,
                perDay.stream().mapToLong(DayStat::minutes).sum(),
                perDay.stream().mapToInt(DayStat::sessions).sum(),
                perDay.stream().mapToInt(DayStat::cardsReviewed).sum(),
                perDay.stream().mapToInt(DayStat::cardsCorrect).sum(),
                perDay.stream().mapToInt(DayStat::planItemsDone).sum(),
                perDay.stream().mapToLong(DayStat::points).sum(),
                (int) perDay.stream().filter(d -> d.minutes() > 0 || d.cardsReviewed() > 0 || d.planItemsDone() > 0).count(),
                decks, open.size(), upcomingTests,
                recent.stream().sorted(java.util.Comparator.comparing(Recent::startedAt).reversed()).limit(8).toList());
    }
}
