package se.ostberg.laxcoach.planning;

import se.ostberg.laxcoach.homework.Homework;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Ren logik för vilka dagar det går att plugga, och en enkel reservplan utan Claude. */
public final class PlanDays {

    public record Draft(LocalDate date, String task, int minutes, PlanItem.Type type) {
    }

    private PlanDays() {
    }

    /**
     * Dagar från idag till dagen före inlämning/prov, filtrerat på pluggdagar.
     * Finns inga sådana dagar (t.ex. allt ska in imorgon och idag är ledig dag) används idag.
     */
    public static List<LocalDate> available(LocalDate today, LocalDate due, Set<DayOfWeek> studyDays) {
        List<LocalDate> days = new ArrayList<>();
        if (due == null || !due.isAfter(today)) {
            days.add(today);
            return days;
        }
        for (LocalDate d = today; d.isBefore(due); d = d.plusDays(1)) {
            if (studyDays == null || studyDays.isEmpty() || studyDays.contains(d.getDayOfWeek())) {
                days.add(d);
            }
        }
        if (days.isEmpty()) {
            days.add(today);
        }
        return days;
    }

    /**
     * Reservplan om Claude inte svarar: delsteg fördelas jämnt över dagarna.
     * Inför prov sparas sista dagen till repetition (och kortleken, om det finns en).
     */
    public static List<Draft> fallback(Homework h, List<String> steps, List<LocalDate> days, boolean hasDeck) {
        List<Draft> out = new ArrayList<>();
        boolean test = h.getKind() == Homework.Kind.TEST;
        List<LocalDate> learnDays = test && days.size() >= 2 ? days.subList(0, days.size() - 1) : days;

        List<String> work = steps.isEmpty() ? List.of("Börja på: " + h.getTitle()) : steps;
        for (int i = 0; i < work.size(); i++) {
            int dayIndex = (int) ((long) i * learnDays.size() / work.size());
            out.add(new Draft(learnDays.get(dayIndex), work.get(i), 15, PlanItem.Type.OTHER));
        }
        if (test) {
            LocalDate last = days.get(days.size() - 1);
            if (hasDeck) {
                out.add(new Draft(last, "Repetera kortleken till " + h.getTitle(), 10, PlanItem.Type.FLASHCARDS));
            }
            out.add(new Draft(last, "Förhör dig själv på det viktigaste i " + h.getTitle(), 15, PlanItem.Type.REVIEW));
        }
        return out;
    }
}
