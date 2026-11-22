package se.ostberg.pluggis.settings;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Elevens namn och coachens ton (föräldravyn → Inställningar). */
@RestController
@RequestMapping("/api/student")
public class StudentController {

    public record Update(String name, String tone, String extra, Integer maxMinutesPerDay, java.util.List<String> studyDays,
                         String studyTime, String studyCue) {
    }

    private final StudentProfile profile;
    private final se.ostberg.pluggis.planning.PlanningService planning;
    private final se.ostberg.pluggis.student.StudentService students;

    public StudentController(StudentProfile profile, se.ostberg.pluggis.planning.PlanningService planning,
                             se.ostberg.pluggis.student.StudentService students) {
        this.profile = profile;
        this.planning = planning;
        this.students = students;
    }

    @GetMapping
    public Map<String, Object> get() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("name", profile.name());
        m.put("tone", profile.tone());
        m.put("extra", profile.extra());
        m.put("tones", StudentProfile.TONES.values());
        m.put("maxMinutesPerDay", planning.maxMinutesPerDay());
        m.put("studyDays", planning.studyDays().stream().sorted().map(Enum::name).toList());
        var st = students.current();
        m.put("studyTime", st.studyTimeOrDefault().toString());
        m.put("studyCue", st.getStudyCue());
        return m;
    }

    @PutMapping
    public Map<String, Object> put(@RequestBody Update body) {
        profile.update(body.name(), body.tone(), body.extra());
        if (body.studyTime() != null || body.studyCue() != null) {
            var s = students.current();
            if (body.studyTime() != null) {
                try {
                    s.setStudyTime(java.time.LocalTime.parse(body.studyTime().trim()).withSecond(0).toString());
                } catch (java.time.format.DateTimeParseException e) {
                    throw new IllegalArgumentException("Pluggtiden ska vara HH:MM, t.ex. 16:15");
                }
            }
            if (body.studyCue() != null) {
                String c = body.studyCue().replaceAll("\\s+", " ").trim();
                s.setStudyCue(c.length() > 80 ? c.substring(0, 80) : c);
            }
            students.save(s);
        }
        if (body.maxMinutesPerDay() != null || body.studyDays() != null) {
            var s = students.current();
            if (body.maxMinutesPerDay() != null) {
                s.setMaxMinutesPerDay(Math.max(10, Math.min(240, body.maxMinutesPerDay())));
            }
            if (body.studyDays() != null) {
                var days = body.studyDays().stream().map(d -> {
                    try {
                        return java.time.DayOfWeek.valueOf(d.trim().toUpperCase(java.util.Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        return null;
                    }
                }).filter(java.util.Objects::nonNull).distinct().sorted().map(Enum::name).toList();
                if (days.isEmpty()) {
                    throw new IllegalArgumentException("Välj minst en pluggdag");
                }
                s.setStudyDays(String.join(",", days));
            }
            students.save(s);
        }
        return get();
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    public org.springframework.http.ResponseEntity<Map<String, String>> bad(IllegalArgumentException e) {
        return org.springframework.http.ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
