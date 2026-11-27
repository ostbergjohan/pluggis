package se.ostberg.pluggis.planning;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class PlanReadingTest {

    @Test
    void sidornaLasesUrMomentet() {
        assertThat(PlanningService.pagesIn("Läs s. 40–45 om fotosyntesen")).containsExactly(40, 45);
        assertThat(PlanningService.pagesIn("Läs sid 12-14")).containsExactly(12, 14);
        assertThat(PlanningService.pagesIn("Läs sidan 7")).containsExactly(7, 7);
        assertThat(PlanningService.pagesIn("Läs sidorna 3 och 4")).containsExactly(3, 4);
        assertThat(PlanningService.pagesIn("Läs s.112 till 118")).containsExactly(112, 118);
        assertThat(PlanningService.pagesIn("Läs 182-187")).containsExactly(182, 187);
        assertThat(PlanningService.pagesIn("Läs s. 182–187 i Utkik")).containsExactly(182, 187);
        assertThat(PlanningService.pagesIn("Gör ett snabbquiz")).isNull();
        // engelska elever
        assertThat(PlanningService.pagesIn("Read p. 40–45 about photosynthesis")).containsExactly(40, 45);
        assertThat(PlanningService.pagesIn("Read pages 12 to 14")).containsExactly(12, 14);
        assertThat(PlanningService.pagesIn("Read pp. 3-4")).containsExactly(3, 4);
        assertThat(PlanningService.pagesIn("Practice session: photosynthesis concepts")).isNull();
    }

    @Test
    void lasmomentFarMinuterEfterUpplasningstiden() {
        var pages = new TreeMap<Integer, Double>();
        for (int p = 40; p <= 50; p++) {
            pages.put(p, 3.0);   // 3 min per sida
        }
        LocalDate d = LocalDate.of(2026, 12, 1);
        var drafts = List.of(
                new PlanDays.Draft(d, "Läs s. 40–44", 20, PlanItem.Type.READ),    // 15 min → 15 * 1,25 + 2 = 21
                new PlanDays.Draft(d, "Läs s. 45", 20, PlanItem.Type.READ),       // 3 min → minst 15
                new PlanDays.Draft(d, "Läs s. 40–50", 20, PlanItem.Type.READ),    // 33 min → högst 30
                new PlanDays.Draft(d, "Läs s. 90–95", 20, PlanItem.Type.READ),    // okända sidor → orört
                new PlanDays.Draft(d, "Gör ett snabbquiz", 20, PlanItem.Type.PRACTICE));
        var out = PlanningService.readingMinutes(drafts, pages);
        assertThat(out).extracting(PlanDays.Draft::minutes).containsExactly(21, 15, 30, 20, 20);
    }

    @Test
    void utanSidorRorsIngenting() {
        LocalDate d = LocalDate.of(2026, 12, 1);
        var drafts = List.of(new PlanDays.Draft(d, "Läs s. 40–44", 20, PlanItem.Type.READ));
        assertThat(PlanningService.readingMinutes(drafts, new TreeMap<>())).isEqualTo(drafts);
    }
}
