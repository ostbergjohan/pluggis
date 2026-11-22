package se.ostberg.pluggis.rewards;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Poäng och veckopeng – för föräldravyn och för enhetens poängskärm. */
@RestController
@RequestMapping("/api/devices/{deviceId}/rewards")
public class RewardsController {

    public record BonusRequest(int points, String reason) {
    }

    public record PayoutRequest(BigDecimal amountSek, String note) {
    }

    private final RewardsService rewards;

    public RewardsController(RewardsService rewards) {
        this.rewards = rewards;
    }

    @GetMapping
    public RewardsService.WeekSummary week(@PathVariable String deviceId) {
        return rewards.week(deviceId);
    }

    @GetMapping("/events")
    public List<PointEvent> events(@PathVariable String deviceId) {
        return rewards.recent(deviceId);
    }

    @PostMapping("/bonus")
    public RewardsService.WeekSummary bonus(@PathVariable String deviceId, @RequestBody BonusRequest body) {
        rewards.bonus(deviceId, body.points(), body.reason());
        return rewards.week(deviceId);
    }

    /** Markera utbetalt. Utan belopp markeras hela veckans obetalda summa. */
    @PostMapping("/payouts")
    public Payout markPaid(@PathVariable String deviceId, @RequestBody(required = false) PayoutRequest body) {
        return rewards.markPaid(deviceId, body == null ? null : body.amountSek(), body == null ? null : body.note());
    }

    public record ResetRequest(String scope, Boolean payouts) {
    }

    /** Nollställ veckans (scope=week) eller alla (scope=all) poäng; payouts=true tar även bort utbetalningar. */
    @PostMapping("/reset")
    public Map<String, Object> reset(@PathVariable String deviceId, @RequestBody(required = false) ResetRequest body) {
        String scope = body == null || body.scope() == null ? "week" : body.scope();
        long removed = rewards.reset(deviceId, scope, body != null && Boolean.TRUE.equals(body.payouts()));
        return Map.of("removed", removed, "week", rewards.week(deviceId));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/events/{eventId}")
    public org.springframework.http.ResponseEntity<Void> deleteEvent(@PathVariable String deviceId, @PathVariable long eventId) {
        return rewards.deleteEvent(deviceId, eventId)
                ? org.springframework.http.ResponseEntity.noContent().build()
                : org.springframework.http.ResponseEntity.notFound().build();
    }

    @GetMapping("/payouts")
    public List<Payout> payouts(@PathVariable String deviceId) {
        return rewards.payouts(deviceId);
    }
}
