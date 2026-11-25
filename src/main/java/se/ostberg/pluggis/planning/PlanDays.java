package se.ostberg.pluggis.planning;

import se.ostberg.pluggis.homework.Homework;

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

    /** Resultat av {@link #fitToBudget}: momenten och hur många minuter som inte fick plats under taket. */
    public record Fitted(List<Draft> drafts, int overflowMinutes) {
    }

    /**
     * Hård spärr för max minuter per dag (alla läxor tillsammans). Ett moment som inte ryms flyttas
     * till närmaste tidigare dag med plats, annars närmaste senare. Ryms det ingenstans läggs det på
     * dagen med minst plugg och räknas som överskott.
     */
    public static Fitted fitToBudget(List<Draft> drafts, List<LocalDate> days, java.util.Map<LocalDate, Integer> busy, int max) {
        java.util.Map<LocalDate, Integer> load = new java.util.HashMap<>();
        days.forEach(d -> load.put(d, busy.getOrDefault(d, 0)));
        List<Draft> sorted = new ArrayList<>(drafts);
        sorted.sort(java.util.Comparator.comparing(Draft::date));
        List<Draft> out = new ArrayList<>();
        int overflow = 0;
        for (Draft d : sorted) {
            LocalDate target = d.date();
            if (load.getOrDefault(target, 0) + d.minutes() > max) {
                target = null;
                int idx = days.indexOf(d.date());
                for (int i = idx - 1; i >= 0 && target == null; i--) {
                    if (load.get(days.get(i)) + d.minutes() <= max) target = days.get(i);
                }
                for (int i = idx + 1; i < days.size() && target == null; i++) {
                    if (load.get(days.get(i)) + d.minutes() <= max) target = days.get(i);
                }
                if (target == null) {
                    target = days.stream().min(java.util.Comparator.comparing(load::get)).orElse(d.date());
                    overflow += Math.max(0, load.getOrDefault(target, 0) + d.minutes() - max);
                }
            }
            load.merge(target, d.minutes(), Integer::sum);
            out.add(target.equals(d.date()) ? d : new Draft(target, d.task(), d.minutes(), d.type()));
        }
        out.sort(java.util.Comparator.comparing(Draft::date));
        return new Fitted(out, overflow);
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
                out.add(new Draft(last, "Repetera kortleken till " + h.getTitle(), 15, PlanItem.Type.FLASHCARDS));
            }
            out.add(new Draft(last, "Förhör dig själv på det viktigaste i " + h.getTitle(), 15, PlanItem.Type.REVIEW));
        }
        return out;
    }
}
