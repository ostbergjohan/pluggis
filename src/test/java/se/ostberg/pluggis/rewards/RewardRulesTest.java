package se.ostberg.pluggis.rewards;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RewardRulesTest {

    @Test
    void sessionPointsOnlyForRealTimeAndCappedAtPlan() {
        assertThat(RewardRules.sessionMinutePoints(2, 15)).isZero();
        assertThat(RewardRules.sessionMinutePoints(10, 15)).isEqualTo(10);
        assertThat(RewardRules.sessionMinutePoints(40, 15)).isEqualTo(15);
        assertThat(RewardRules.sessionCompleted(15, 15)).isTrue();
        assertThat(RewardRules.sessionCompleted(14, 15)).isFalse();
    }

    @Test
    void streakCountsConsecutiveDaysEndingToday() {
        LocalDate today = LocalDate.of(2026, 11, 21);
        List<LocalDate> days = List.of(today, today.minusDays(1), today.minusDays(2), today.minusDays(5));

        assertThat(RewardRules.streak(today, days)).isEqualTo(3);
        assertThat(RewardRules.streak(today, List.of(today.minusDays(1)))).isZero();
        assertThat(RewardRules.streakBonus(1)).isZero();
        assertThat(RewardRules.streakBonus(3)).isEqualTo(9);
        assertThat(RewardRules.streakBonus(30)).isEqualTo(RewardRules.STREAK_MAX_DAYS_COUNTED * RewardRules.STREAK_POINTS_PER_DAY);
    }

    @Test
    void moneyIsCappedPerWeek() {
        BigDecimal rate = new BigDecimal("0.10");

        assertThat(RewardRules.earnedSek(120, rate, new BigDecimal("50"))).isEqualByComparingTo("12.00");
        assertThat(RewardRules.earnedSek(900, rate, new BigDecimal("50"))).isEqualByComparingTo("50.00");
        assertThat(RewardRules.earnedSek(900, rate, BigDecimal.ZERO)).isEqualByComparingTo("90.00");
    }

    @Test
    void weekStartsOnMonday() {
        assertThat(RewardsService.weekStart(LocalDate.of(2026, 11, 21))).isEqualTo(LocalDate.of(2026, 11, 16));
        assertThat(RewardsService.weekStart(LocalDate.of(2026, 11, 16))).isEqualTo(LocalDate.of(2026, 11, 16));
    }
}
