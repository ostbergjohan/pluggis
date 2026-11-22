package se.ostberg.pluggis.student;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import se.ostberg.pluggis.settings.SettingsService;

import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Elever: skapa, ändra, koder och vilken elev ett anrop gäller. */
@Service
public class StudentService {

    private static final Logger log = LoggerFactory.getLogger(StudentService.class);
    /** Inga 0/O, 1/I/L – lätt att läsa och skriva på en telefon. */
    static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    public static final int CODE_LENGTH = 5;

    private final StudentRepository repo;
    private final SettingsService settings;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();

    public StudentService(StudentRepository repo, SettingsService settings, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.repo = repo;
        this.settings = settings;
        this.jdbc = jdbc;
    }

    /**
     * Ser till att det finns minst en elev. Första gången flyttas tidigare inställningar
     * (namn, ton, kalender, chattens enhet) över till den.
     */
    @Transactional
    public Student ensureDefault() {
        List<Student> all = repo.findAllByOrderByIdAsc();
        Student first;
        if (all.isEmpty()) {
            String device = settings.get("chat.deviceId").orElse("tab5-1");
            first = new Student(settings.get("student.name").orElse(""), device, newCode());
            first.setTone(settings.get("coach.tone").orElse(null));
            first.setExtra(settings.get("coach.extra").orElse(null));
            first.setCalendarUrl(settings.get(SettingsService.CALENDAR_URL).orElse(null));
            settings.get(SettingsService.CALENDAR_ONLY_SCHOOLWORK).map(Boolean::parseBoolean)
                    .ifPresent(first::setCalendarOnlySchoolwork);
            first = repo.save(first);
            log.info("Skapade första eleven (id {}, enhet {})", first.getId(), first.getDeviceId());
        } else {
            first = all.get(0);
        }
        StudentContext.setDefault(first.getId());
        return first;
    }

    @Transactional(readOnly = true)
    public List<Student> all() {
        return repo.findAllByOrderByIdAsc();
    }

    @Transactional(readOnly = true)
    public Optional<Student> byId(Long id) {
        return id == null ? Optional.empty() : repo.findById(id);
    }

    @Transactional(readOnly = true)
    public Optional<Student> byCode(String code) {
        String c = normalizeCode(code);
        return c == null ? Optional.empty() : repo.findByCodeIgnoreCase(c);
    }

    @Transactional(readOnly = true)
    public Optional<Student> byDeviceId(String deviceId) {
        return deviceId == null || deviceId.isBlank() ? Optional.empty() : repo.findByDeviceId(deviceId.trim());
    }

    /** Eleven anropet gäller. */
    @Transactional(readOnly = true)
    public Student current() {
        return repo.findById(StudentContext.currentOrDefault())
                .or(() -> repo.findAllByOrderByIdAsc().stream().findFirst())
                .orElseThrow(() -> new IllegalStateException("Ingen elev finns"));
    }

    @Transactional
    public Student create(String name, String deviceId) {
        String device = deviceId == null || deviceId.isBlank() ? suggestDeviceId(name) : deviceId.trim();
        if (repo.findByDeviceId(device).isPresent()) {
            throw new IllegalArgumentException("Enhets-id " + device + " används redan av en annan elev");
        }
        return repo.save(new Student(clean(name, 40), device, newCode()));
    }

    @Transactional
    public Student update(long id, String name, String deviceId) {
        Student s = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("Hittade ingen elev"));
        if (name != null) {
            s.setName(clean(name, 40));
        }
        if (deviceId != null && !deviceId.isBlank() && !deviceId.trim().equals(s.getDeviceId())) {
            if (repo.findByDeviceId(deviceId.trim()).isPresent()) {
                throw new IllegalArgumentException("Enhets-id " + deviceId.trim() + " används redan av en annan elev");
            }
            s.setDeviceId(deviceId.trim());
        }
        return s;
    }

    @Transactional
    public Student newCode(long id) {
        Student s = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("Hittade ingen elev"));
        s.setCode(newCode());
        return s;
    }

    @Transactional
    public void delete(long id) {
        if (repo.count() <= 1) {
            throw new IllegalArgumentException("Det måste finnas minst en elev");
        }
        // Elevens läxor, plan, material och flashkort tas bort med eleven
        for (String table : new String[]{"plan_item", "material", "card", "deck", "homework"}) {
            jdbc.update("delete from " + table + " where student_key = ?", String.valueOf(id));
        }
        repo.deleteById(id);
        ensureDefault();
    }

    @Transactional
    public Student save(Student s) {
        return repo.save(s);
    }

    String newCode() {
        for (int attempt = 0; attempt < 50; attempt++) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < CODE_LENGTH; i++) {
                sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            if (repo.findByCodeIgnoreCase(sb.toString()).isEmpty()) {
                return sb.toString();
            }
        }
        throw new IllegalStateException("Kunde inte skapa en unik kod");
    }

    /** "k7m 2q" → "K7M2Q"; null om det inte kan vara en kod. */
    public static String normalizeCode(String code) {
        if (code == null) {
            return null;
        }
        String c = code.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        return c.length() == CODE_LENGTH && c.chars().allMatch(ch -> ALPHABET.indexOf(ch) >= 0
                || "01IOL".indexOf(ch) >= 0) ? c : null;
    }

    private String suggestDeviceId(String name) {
        String base = name == null ? "" : java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (base.isBlank()) {
            base = "elev";
        }
        String candidate = base;
        for (int i = 2; repo.findByDeviceId(candidate).isPresent(); i++) {
            candidate = base + "-" + i;
        }
        return candidate;
    }

    private static String clean(String s, int max) {
        String t = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
