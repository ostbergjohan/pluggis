package se.ostberg.pluggis.planning;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlanBudgetTest {

    private static final LocalDate MON = LocalDate.of(2026, 11, 23);
    private static final List<LocalDate> DAYS = List.of(MON, MON.plusDays(1), MON.plusDays(2));

    private static PlanDays.Draft d(LocalDate date, int min) {
        return new PlanDays.Draft(date, "x" + min, min, PlanItem.Type.OTHER);
    }

    private static int sum(List<PlanDays.Draft> list, LocalDate day, Map<LocalDate, Integer> busy) {
        return busy.getOrDefault(day, 0) + list.stream().filter(x -> x.date().equals(day)).mapToInt(PlanDays.Draft::minutes).sum();
    }

    @Test
    void flyttarDetSomInteRymsSaMaxHaller() {
        Map<LocalDate, Integer> busy = Map.of(MON.plusDays(2), 30);
        var f = PlanDays.fitToBudget(List.of(d(MON.plusDays(2), 20), d(MON.plusDays(2), 15)), DAYS, busy, 45);
        assertThat(f.overflowMinutes()).isZero();
        DAYS.forEach(day -> assertThat(sum(f.drafts(), day, busy)).isLessThanOrEqualTo(45));
        assertThat(f.drafts()).hasSize(2);
    }

    @Test
    void rapporterarOverskottNarDetInteGarAttFaPlats() {
        var drafts = List.of(d(MON, 30), d(MON, 30), d(MON.plusDays(1), 30), d(MON.plusDays(2), 30));
        var f = PlanDays.fitToBudget(drafts, DAYS, Map.of(), 30);
        assertThat(f.drafts()).hasSize(4);
        assertThat(f.overflowMinutes()).isEqualTo(30);
    }

    @Test
    void rorInteEnPlanSomRedanRyms() {
        var drafts = List.of(d(MON, 15), d(MON.plusDays(1), 20));
        var f = PlanDays.fitToBudget(drafts, DAYS, Map.of(), 45);
        assertThat(f.drafts()).isEqualTo(drafts);
    }
}
