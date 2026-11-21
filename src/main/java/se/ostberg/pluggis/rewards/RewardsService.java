package se.ostberg.pluggis.rewards;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import se.ostberg.pluggis.config.PluggisProperties;
import se.ostberg.pluggis.flashcards.FlashcardService;
import se.ostberg.pluggis.session.StudySession;
import se.ostberg.pluggis.session.StudySessionService;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Optional;

/**
 * Poäng för pluggande, med valfri koppling till veckopeng.
 * Lyssnar på händelser från pass och flashkort, så resten av koden behöver inte känna till poäng.
 */
@Service
public class RewardsService {

    /** Publiceras när poäng delats ut – enheten kan visa "+3" och konfetti. */
    public record PointsAwarded(String deviceId, int points, long weekTotal, String reason) {
    }

    public record WeekSummary(
            String deviceId, LocalDate weekStart, long weekPoints, long allTimePoints, int streakDays,
            BigDecimal earnedSek, BigDecimal paidSek, BigDecimal unpaidSek,
            BigDecimal sekPerPoint, BigDecimal weeklyCapSek) {
    }

    private final PointEventRepository points;
    private final PayoutRepository payouts;
    private final PluggisProperties props;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Autowired
    public RewardsService(PointEventRepository points, PayoutRepository payouts, PluggisProperties props,
                          ApplicationEventPublisher events) {
        this(points, payouts, props, events, Clock.systemUTC());
    }

    RewardsService(PointEventRepository points, PayoutRepository payouts, PluggisProperties props,
                   ApplicationEventPublisher events, Clock clock) {
        this.points = points;
        this.payouts = payouts;
        this.props = props;
        this.events = events;
        this.clock = clock;
    }

    // ---- Händelser som ger poäng ----

    @EventListener
    @Transactional
    public void onCardGraded(FlashcardService.CardGraded e) {
        String ref = "card:" + e.card().getId();
        if (points.countByDeviceIdAndRefKeyAndAwardedOn(e.deviceId(), ref, today()) >= RewardRules.CARD_MAX_SCORED_PER_DAY) {
            return;
        }
        int p = RewardRules.CARD_ATTEMPT + (e.correct() ? RewardRules.CARD_CORRECT : 0);
        award(e.deviceId(), e.correct() ? PointEvent.Kind.CARD_CORRECT : PointEvent.Kind.CARD_ATTEMPT, p,
                e.correct() ? "Rätt på ett kort" : "Bra försök", ref);
    }

    @EventListener
    @Transactional
    public void onSessionFinished(StudySessionService.SessionFinished e) {
        StudySession s = e.session();
        if (s.getFinishedAt() == null || s.getStartedAt() == null) {
            return;
        }
        long minutes = Duration.between(s.getStartedAt(), s.getFinishedAt()).toMinutes();
        int minutePoints = RewardRules.sessionMinutePoints(minutes, s.getPlannedMinutes());
        if (minutePoints == 0) {
            return;
        }
        String ref = "session:" + s.getId();
        award(e.deviceId(), PointEvent.Kind.SESSION_MINUTES, minutePoints, minutes + " minuters plugg", ref);
        if (RewardRules.sessionCompleted(minutes, s.getPlannedMinutes())) {
            award(e.deviceId(), PointEvent.Kind.SESSION_COMPLETED, RewardRules.SESSION_COMPLETED_BONUS,
                    "Höll hela passet", ref);
        }
        awardStreakIfFirstToday(e.deviceId());
    }

    @Transactional
    public void onHomeworkDone(String deviceId, long homeworkId, String title) {
        String ref = "homework:" + homeworkId;
        if (points.countByDeviceIdAndRefKeyAndAwardedOn(deviceId, ref, today()) > 0) {
            return;
        }
        award(deviceId, PointEvent.Kind.HOMEWORK_DONE, RewardRules.HOMEWORK_DONE, "Klar med " + title, ref);
    }

    @Transactional
    public void onPlanItemDone(String deviceId, long planItemId, String task) {
        String ref = "plan:" + planItemId;
        if (points.countByDeviceIdAndRefKeyAndAwardedOn(deviceId, ref, today()) > 0) {
            return;
        }
        award(deviceId, PointEvent.Kind.PLAN_ITEM_DONE, RewardRules.PLAN_ITEM_DONE, "Klart: " + task, ref);
    }

    /** En förälder kan dela ut extrapoäng ("bra jobbat med provet!"). */
    @Transactional
    public void bonus(String deviceId, int bonusPoints, String reason) {
        if (bonusPoints <= 0) {
            throw new IllegalArgumentException("Bonus måste vara positiv – poäng dras aldrig av");
        }
        award(deviceId, PointEvent.Kind.BONUS, bonusPoints, reason == null ? "Bonus" : reason, null);
    }

    private void awardStreakIfFirstToday(String deviceId) {
        LocalDate today = today();
        if (points.existsByDeviceIdAndKindAndAwardedOn(deviceId, PointEvent.Kind.STREAK, today)) {
            return;
        }
        List<LocalDate> days = points.daysWithKind(deviceId, PointEvent.Kind.SESSION_MINUTES, today.minusDays(60));
        int bonus = RewardRules.streakBonus(RewardRules.streak(today, days));
        if (bonus > 0) {
            award(deviceId, PointEvent.Kind.STREAK, bonus, RewardRules.streak(today, days) + " dagar i rad!", null);
        }
    }

    private void award(String deviceId, PointEvent.Kind kind, int p, String reason, String ref) {
        points.save(new PointEvent(deviceId, kind, p, reason, ref, today(), clock.instant()));
        events.publishEvent(new PointsAwarded(deviceId, p, weekPoints(deviceId), reason));
    }

    // ---- Läsning och utbetalning ----

    @Transactional(readOnly = true)
    public WeekSummary week(String deviceId) {
        LocalDate start = weekStart(today());
        long weekPoints = points.sumPoints(deviceId, start, start.plusDays(6));
        BigDecimal earned = RewardRules.earnedSek(weekPoints, rate(), cap());
        BigDecimal paid = Optional.ofNullable(payouts.paidForWeek(deviceId, start)).orElse(BigDecimal.ZERO);
        BigDecimal unpaid = earned.subtract(paid).max(BigDecimal.ZERO);
        int streak = RewardRules.streak(today(),
                points.daysWithKind(deviceId, PointEvent.Kind.SESSION_MINUTES, today().minusDays(60)));
        return new WeekSummary(deviceId, start, weekPoints, points.sumAllPoints(deviceId), streak,
                earned, paid, unpaid, rate(), cap());
    }

    @Transactional(readOnly = true)
    public List<PointEvent> recent(String deviceId) {
        return points.findTop50ByDeviceIdOrderByCreatedAtDesc(deviceId);
    }

    @Transactional
    public Payout markPaid(String deviceId, BigDecimal amountSek, String note) {
        BigDecimal amount = amountSek != null ? amountSek : week(deviceId).unpaidSek();
        return payouts.save(new Payout(deviceId, amount, weekStart(today()), note, clock.instant()));
    }

    @Transactional(readOnly = true)
    public List<Payout> payouts(String deviceId) {
        return payouts.findTop20ByDeviceIdOrderByPaidAtDesc(deviceId);
    }

    @Transactional(readOnly = true)
    public String summaryForPrompt(String deviceId) {
        WeekSummary w = week(deviceId);
        String money = props.rewards() != null && props.rewards().enabled()
                ? ", motsvarar " + w.earnedSek().stripTrailingZeros().toPlainString() + " kr" : "";
        return w.weekPoints() + " poäng den här veckan" + money + ", streak " + w.streakDays() + " dagar";
    }

    private long weekPoints(String deviceId) {
        LocalDate start = weekStart(today());
        return points.sumPoints(deviceId, start, start.plusDays(6));
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(props.zone()));
    }

    static LocalDate weekStart(LocalDate day) {
        return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private BigDecimal rate() {
        return props.rewards() == null || props.rewards().sekPerPoint() == null
                ? BigDecimal.ZERO : props.rewards().sekPerPoint();
    }

    private BigDecimal cap() {
        return props.rewards() == null ? null : props.rewards().weeklyCapSek();
    }
}
