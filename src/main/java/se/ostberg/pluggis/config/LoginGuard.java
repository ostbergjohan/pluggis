package se.ostberg.pluggis.config;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spärr mot att gissa elevkoder: högst {@code max} felaktiga försök per adress och tidsfönster.
 * En kod med 5 tecken (31 möjliga tecken) har ~29 miljoner kombinationer – med 20 försök per 10 minuter
 * tar det i snitt hundratals år att gissa rätt.
 */
public class LoginGuard {

    /** Delad spärr för elevkoder – samma räkning för /api och Tab5:s WebSocket. */
    public static final LoginGuard STUDENT_CODES = new LoginGuard(20, Duration.ofMinutes(10), 300);

    private final int max;
    private final int globalMax;
    private final Duration window;
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();
    private final Deque<Instant> all = new ArrayDeque<>();

    public LoginGuard(int max, Duration window) {
        this(max, window, max * 20);
    }

    /** {@code globalMax}: felaktiga försök från alla adresser tillsammans (skydd mot gissning från många adresser). */
    public LoginGuard(int max, Duration window, int globalMax) {
        this.max = max;
        this.window = window;
        this.globalMax = globalMax;
    }

    public boolean allow(String key) {
        synchronized (all) {
            prune(all);
            if (all.size() >= globalMax) {
                return false;
            }
        }
        Deque<Instant> q = failures.get(key);
        if (q == null) {
            return true;
        }
        synchronized (q) {
            prune(q);
            return q.size() < max;
        }
    }

    public void failure(String key) {
        synchronized (all) {
            prune(all);
            all.addLast(Instant.now());
        }
        Deque<Instant> q = failures.computeIfAbsent(key == null ? "?" : key, k -> new ArrayDeque<>());
        synchronized (q) {
            prune(q);
            q.addLast(Instant.now());
        }
        if (failures.size() > 10_000) {
            failures.clear(); // skydd mot att minnet växer
        }
    }

    public void success(String key) {
        if (key != null) {
            failures.remove(key);
        }
    }

    private void prune(Deque<Instant> q) {
        Instant cutoff = Instant.now().minus(window);
        while (!q.isEmpty() && q.peekFirst().isBefore(cutoff)) {
            q.removeFirst();
        }
    }
}
