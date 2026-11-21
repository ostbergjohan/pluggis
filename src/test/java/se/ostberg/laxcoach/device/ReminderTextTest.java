package se.ostberg.laxcoach.device;

import org.junit.jupiter.api.Test;
import se.ostberg.laxcoach.homework.Homework;
import se.ostberg.laxcoach.planning.PlanItem;
import se.ostberg.laxcoach.planning.PlanningService;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReminderTextTest {

    @Test
    void mentionsFirstTaskAndWhatIsDueTomorrow() {
        LocalDate day = LocalDate.of(2026, 11, 23);
        var item = new PlanItem(1L, day, "Läs s. 40–48 och skriv 3 nyckelord", 15, PlanItem.Type.READ);
        var other = new PlanItem(1L, day, "Gör 5 begreppskort", 10, PlanItem.Type.WRITE);
        var today = List.of(
                new PlanningService.TodayItem(item, "Prov fotosyntes", day.plusDays(4), Homework.Kind.TEST, false),
                new PlanningService.TodayItem(other, "Prov fotosyntes", day.plusDays(4), Homework.Kind.TEST, false));
        Homework glosor = new Homework("Engelska", "Glosor kap 3", null, day.plusDays(1), null, Homework.Source.MANUAL);

        String text = ReminderService.startText(today, List.of(glosor));

        assertThat(text).contains("Läs s. 40–48").contains("1 sak till").contains("Glosor kap 3").contains("imorgon");
    }

    @Test
    void nothingToSayGivesNull() {
        assertThat(ReminderService.startText(List.of(), List.of())).isNull();
    }
}
