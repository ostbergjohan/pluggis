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

    public record Update(String name, String tone, String extra) {
    }

    private final StudentProfile profile;

    public StudentController(StudentProfile profile) {
        this.profile = profile;
    }

    @GetMapping
    public Map<String, Object> get() {
        return Map.of("name", profile.name(), "tone", profile.tone(), "extra", profile.extra(),
                "tones", StudentProfile.TONES.values());
    }

    @PutMapping
    public Map<String, Object> put(@RequestBody Update body) {
        profile.update(body.name(), body.tone(), body.extra());
        return get();
    }
}
