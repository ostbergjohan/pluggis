package se.ostberg.laxcoach.device;

/**
 * Meddelanden server → enhet. Se docs/protokoll.md.
 * Alla har fältet "type" så att firmwaren kan switcha på det.
 */
public final class DeviceMessages {

    private DeviceMessages() {
    }

    /** Text som ska visas och läsas upp. */
    public record Say(String type, String text) {
        public Say(String text) {
            this("say", text);
        }
    }

    /** Tidsstapel/LED-list: andel kvar = remainingSeconds / totalSeconds. */
    public record Timer(String type, long remainingSeconds, long totalSeconds, String task) {
        public Timer(long remainingSeconds, long totalSeconds, String task) {
            this("timer", remainingSeconds, totalSeconds, task);
        }
    }

    public record SessionState(String type, String state, String task, int minutes) {
        public static SessionState started(String task, int minutes) {
            return new SessionState("session", "started", task, minutes);
        }

        public static SessionState timeUp(String task, int minutes) {
            return new SessionState("session", "time_up", task, minutes);
        }

        public static SessionState finished(String task, int minutes) {
            return new SessionState("session", "finished", task, minutes);
        }
    }

    /** Be enheten fota (med nedräkning) och POST:a bilden till /api/homework/photo. */
    public record TakePhoto(String type, int countdownSeconds) {
        public TakePhoto(int countdownSeconds) {
            this("take_photo", countdownSeconds);
        }
    }

    /** Coachen tänker – visa en animation så eleven vet att något händer. */
    public record Thinking(String type) {
        public Thinking() {
            this("thinking");
        }
    }

    public record Error(String type, String message) {
        public Error(String message) {
            this("error", message);
        }
    }
}
