package se.ostberg.pluggis.rewards;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * Poängreglerna samlade på ett ställe, utan Spring – lätta att testa och ändra.
 * Grundtanke: belöna ansträngning (att sätta sig, försöka, hålla på) mer än rätt svar.
 */
public final class RewardRules {

    public static final int CARD_ATTEMPT = 1;
    public static final int CARD_CORRECT = 2;
    /** Samma kort ger poäng högst så här många gånger per dag (stoppar nötning av lätta kort). */
    public static final int CARD_MAX_SCORED_PER_DAY = 2;

    public static final int POINTS_PER_SESSION_MINUTE = 1;
    public static final int SESSION_COMPLETED_BONUS = 5;
    /** Pass kortare än så här ger inga minutpoäng. */
    public static final int MIN_SESSION_MINUTES = 3;

    public static final int HOMEWORK_DONE = 10;

    /** Ett avbockat moment i pluggplanen. */
    public static final int PLAN_ITEM_DONE = 3;
    /** Allt i dagens plan avbockat (även det som låg efter). */
    public static final int DAY_COMPLETE = 5;
    /** Förklarat ett begrepp för Pluggis (lär ut) – en ansträngning, oavsett hur bra det blev. */
    public static final int EXPLAINED = 5;
    /** Gjort ett helt testprov – ansträngningen räknas, inte resultatet. */
    public static final int MOCK_EXAM = 10;
    /** Klarat en snabb övning: Matcha par eller Rätt ordning. */
    public static final int GAME_DONE = 3;

    public static final int STREAK_POINTS_PER_DAY = 3;
    public static final int STREAK_MAX_DAYS_COUNTED = 7;

    private RewardRules() {
    }

    /** Minutpoäng för ett pass: bara tid som faktiskt gått, högst den planerade tiden. */
    public static int sessionMinutePoints(long actualMinutes, int plannedMinutes) {
        if (actualMinutes < MIN_SESSION_MINUTES) {
            return 0;
        }
        return (int) Math.min(actualMinutes, plannedMinutes) * POINTS_PER_SESSION_MINUTE;
    }

    public static boolean sessionCompleted(long actualMinutes, int plannedMinutes) {
        return actualMinutes >= plannedMinutes;
    }

    /** Antal dagar i rad (inklusive idag) med minst ett genomfört pass. */
    public static int streak(LocalDate today, List<LocalDate> daysWithSessionsDesc) {
        int streak = 0;
        LocalDate expected = today;
        for (LocalDate d : daysWithSessionsDesc) {
            if (d.equals(expected)) {
                streak++;
                expected = expected.minusDays(1);
            } else if (d.isBefore(expected)) {
                break;
            }
        }
        return streak;
    }

    /** Streak-bonus delas ut för dag 2 och framåt i en svit. */
    public static int streakBonus(int streak) {
        return streak < 2 ? 0 : Math.min(streak, STREAK_MAX_DAYS_COUNTED) * STREAK_POINTS_PER_DAY;
    }

    /** Veckans intjänade kronor, med tak. */
    public static BigDecimal earnedSek(long weekPoints, BigDecimal sekPerPoint, BigDecimal weeklyCapSek) {
        BigDecimal raw = sekPerPoint.multiply(BigDecimal.valueOf(weekPoints));
        BigDecimal capped = weeklyCapSek == null || weeklyCapSek.signum() <= 0 ? raw : raw.min(weeklyCapSek);
        return capped.setScale(2, RoundingMode.HALF_UP);
    }
}
