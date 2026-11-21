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

    /**
     * Be enheten fota (med nedräkning) och POST:a bilden till uploadPath
     * (/api/homework/photo för uppgifter, /api/flashcards/photo för glosor/anteckningar).
     */
    public record TakePhoto(String type, int countdownSeconds, String uploadPath, String purpose) {
        public static TakePhoto homework() {
            return new TakePhoto("take_photo", 3, "/api/homework/photo", "homework");
        }

        public static TakePhoto flashcards() {
            return new TakePhoto("take_photo", 3, "/api/flashcards/photo", "flashcards");
        }
    }

    /** Visa framsidan av ett flashkort. */
    public record Card(String type, long cardId, String front, String hint) {
        public Card(long cardId, String front, String hint) {
            this("card", cardId, front, hint);
        }
    }

    /** Visa baksidan och om svaret var rätt. */
    public record CardAnswer(String type, long cardId, String back, boolean correct, int box) {
        public CardAnswer(long cardId, String back, boolean correct, int box) {
            this("card_answer", cardId, back, correct, box);
        }
    }

    /** Poäng delades ut – visa "+3" och veckosumman. */
    public record Points(String type, int delta, long weekTotal, String reason) {
        public Points(int delta, long weekTotal, String reason) {
            this("points", delta, weekTotal, reason);
        }
    }

    /** Påminnelse – visa tydligt (gärna med ljud) och läs upp. */
    public record Reminder(String type, String text) {
        public Reminder(String text) {
            this("reminder", text);
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
