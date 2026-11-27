package se.ostberg.pluggis.planning;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** En symbol per ämne, samma som i webben (static/subjects.js) – så att ämnet syns direkt. */
public final class SubjectIcon {

    private static final Map<String, String> ICONS = new LinkedHashMap<>();

    static {
        ICONS.put("matte", "➗");
        ICONS.put("matem", "➗");
        ICONS.put("engelsk", "🇬🇧");
        ICONS.put("svensk", "📖");
        ICONS.put("spansk", "🗣️");
        ICONS.put("tysk", "🗣️");
        ICONS.put("fransk", "🗣️");
        ICONS.put("moderna språk", "🗣️");
        ICONS.put("no", "🧪");
        ICONS.put("biolog", "🧪");
        ICONS.put("kemi", "🧪");
        ICONS.put("fysik", "🧪");
        ICONS.put("so", "🌍");
        ICONS.put("histor", "🌍");
        ICONS.put("geograf", "🌍");
        ICONS.put("samhäll", "🌍");
        ICONS.put("religion", "🌍");
        ICONS.put("teknik", "⚙️");
        ICONS.put("idrott", "⚽");
        ICONS.put("musik", "🎵");
        ICONS.put("bild", "🎨");
        ICONS.put("slöjd", "🧵");
        ICONS.put("hemkunskap", "🍳");
        // English names (students using Pluggis in English)
        ICONS.put("math", "➗");
        ICONS.put("english", "🇬🇧");
        ICONS.put("swedish", "📖");
        ICONS.put("spanish", "🗣️");
        ICONS.put("german", "🗣️");
        ICONS.put("french", "🗣️");
        ICONS.put("languages", "🗣️");
        ICONS.put("science", "🧪");
        ICONS.put("chemi", "🧪");
        ICONS.put("physic", "🧪");
        ICONS.put("social studies", "🌍");
        ICONS.put("technolog", "⚙️");
        ICONS.put("physical education", "⚽");
        ICONS.put("pe", "⚽");
        ICONS.put("music", "🎵");
        ICONS.put("art", "🎨");
        ICONS.put("craft", "🧵");
        ICONS.put("home economics", "🍳");
    }

    private SubjectIcon() {
    }

    public static String of(String subject, String title) {
        String s = ((subject == null ? "" : subject) + " " + (title == null ? "" : title)).toLowerCase(Locale.ROOT);
        for (var e : ICONS.entrySet()) {
            String k = e.getKey();
            // Korta nycklar (no, so) bara som hela ord, annars "kort" i "nobel" osv.
            boolean hit = k.length() <= 3 ? s.matches("(?s).*(^|[^\\p{L}])" + k + "([^\\p{L}]|$).*") : s.contains(k);
            if (hit) {
                return e.getValue();
            }
        }
        return "📘";
    }
}
