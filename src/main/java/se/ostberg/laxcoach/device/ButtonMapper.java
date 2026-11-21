package se.ostberg.laxcoach.device;

import java.util.Optional;

/**
 * Översätter knapptryck till antingen en direkt enhetsåtgärd eller ett
 * syntetiskt meddelande till coachen. Logiken för vad coachen gör ligger
 * i systemprompten, så knapparna går att ändra utan att röra Claude-koden.
 *
 * Standardlayout för M5Stack Dual Button (U025):
 *   röd  ("photo") – klick: fota uppgiften
 *   blå  ("main")  – klick: börja/behöver hjälp, långt: avsluta passet, dubbel: behöver hjälp
 */
public final class ButtonMapper {

    public sealed interface Action permits TakePhoto, SayToCoach {
    }

    public record TakePhoto() implements Action {
    }

    public record SayToCoach(String text) implements Action {
    }

    private ButtonMapper() {
    }

    public static Optional<Action> map(String button, String gesture, boolean sessionActive) {
        String b = button == null ? "" : button.toLowerCase();
        String g = gesture == null ? "click" : gesture.toLowerCase();

        if (b.equals("photo") || b.equals("red")) {
            return Optional.of(new TakePhoto());
        }
        if (b.equals("main") || b.equals("blue")) {
            return switch (g) {
                case "click" -> Optional.of(new SayToCoach(sessionActive
                        ? "(Knapptryck: jag har fastnat och behöver lite hjälp.)"
                        : "(Knapptryck: jag vill börja plugga nu.)"));
                case "long" -> Optional.of(new SayToCoach(
                        "(Långt knapptryck: jag vill avsluta passet.)"));
                case "double" -> Optional.of(new SayToCoach(
                        "(Dubbeltryck: jag behöver hjälp.)"));
                default -> Optional.empty();
            };
        }
        return Optional.empty();
    }
}
