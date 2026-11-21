package se.ostberg.pluggis.coach;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Prata med coachen utan hårdvara – bra för att finslipa prompten med curl. */
@RestController
@RequestMapping("/api/devices/{deviceId}/coach")
public class CoachController {

    public record ChatRequest(@NotBlank String text) {
    }

    public record ChatResponse(String reply) {
    }

    private final CoachService coach;

    public CoachController(CoachService coach) {
        this.coach = coach;
    }

    @PostMapping
    public ChatResponse chat(@PathVariable String deviceId, @Valid @RequestBody ChatRequest body) {
        return new ChatResponse(coach.chat(deviceId, body.text()));
    }

    @DeleteMapping
    public void reset(@PathVariable String deviceId) {
        coach.forget(deviceId);
    }
}
