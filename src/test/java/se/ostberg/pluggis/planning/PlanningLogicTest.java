package se.ostberg.pluggis.planning;

import org.junit.jupiter.api.Test;
import se.ostberg.pluggis.homework.Homework;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlanningLogicTest {

    // Lördag 21 november 2026
    private final LocalDate today = LocalDate.of(2026, 11, 21);
    private final EnumSet<DayOfWeek> weekdaysAndSunday = EnumSet.of(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.SUNDAY);

    @Test
    void availableDaysStopBeforeDueDateAndSkipDaysOff() {
        // Prov fredag 27/11 → sön 22, mån 23, tis 24, ons 25, tor 26 (lördag idag är ledig)
        List<LocalDate> days = PlanDays.available(today, LocalDate.of(2026, 11, 27), weekdaysAndSunday);

        assertThat(days).containsExactly(
                LocalDate.of(2026, 11, 22), LocalDate.of(2026, 11, 23), LocalDate.of(2026, 11, 24),
                LocalDate.of(2026, 11, 25), LocalDate.of(2026, 11, 26));
    }

    @Test
    void dueTomorrowOnADayOffStillGivesToday() {
        assertThat(PlanDays.available(today, today.plusDays(1), weekdaysAndSunday)).containsExactly(today);
        assertThat(PlanDays.available(today, today, weekdaysAndSunday)).containsExactly(today);
    }

    @Test
    void fallbackSpreadsStepsAndKeepsLastDayForReviewBeforeTest() {
        Homework test = new Homework("NO", "Prov fotosyntes", "Kap 4", LocalDate.of(2026, 11, 27),
                List.of("Läs s. 40–48", "Läs s. 49–56", "Läs s. 57–64", "Gör begreppslistan"), Homework.Source.MANUAL);
        test.updateContent(null, null, Homework.Kind.TEST, null);
        List<LocalDate> days = PlanDays.available(today, test.getDueDate(), weekdaysAndSunday);

        List<PlanDays.Draft> plan = PlanDays.fallback(test, test.getSteps(), days, true);

        LocalDate lastDay = days.get(days.size() - 1);
        assertThat(plan).filteredOn(d -> d.date().equals(lastDay))
                .extracting(PlanDays.Draft::type)
                .containsExactly(PlanItem.Type.FLASHCARDS, PlanItem.Type.REVIEW);
        assertThat(plan).filteredOn(d -> d.type() == PlanItem.Type.OTHER)
                .extracting(PlanDays.Draft::date)
                .doesNotContain(lastDay)
                .isSorted();
    }

    @Test
    void validateDropsItemsOutsideAllowedDaysAndClampsMinutes() {
        List<LocalDate> days = List.of(LocalDate.of(2026, 11, 22), LocalDate.of(2026, 11, 23));
        var plan = new PlanningService.GeneratedPlan(List.of(
                new PlanningService.GeneratedItem("2026-11-22", "Läs s. 40–48", 15, "READ"),
                new PlanningService.GeneratedItem("2026-11-28", "Efter provet", 15, "READ"),
                new PlanningService.GeneratedItem("2026-11-23", "Plugga allt", 120, "konstig"),
                new PlanningService.GeneratedItem("inte-ett-datum", "x", 10, "READ")), null);

        List<PlanDays.Draft> drafts = PlanningService.validate(plan, days);

        assertThat(drafts).hasSize(2);
        assertThat(drafts.get(1).minutes()).isEqualTo(30);
        assertThat(drafts.get(1).type()).isEqualTo(PlanItem.Type.OTHER);
    }
}
