package se.ostberg.pluggis.calendar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import se.ostberg.pluggis.config.PluggisProperties;
import se.ostberg.pluggis.homework.Homework;
import se.ostberg.pluggis.homework.HomeworkService;
import se.ostberg.pluggis.planning.PlanningService;
import se.ostberg.pluggis.settings.SettingsService;
import se.ostberg.pluggis.support.TextFix;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Importerar läxor och prov från iCal: en uppladdad .ics-fil (med förhandsgranskning och val)
 * eller en prenumererad länk som synkas varje timme. Samma händelse (UID) uppdateras i stället
 * för att dubbleras, och flyttas ett datum görs planen om.
 *
 * InfoMentor: kopiera kalenderns prenumerationslänk (webcals://im2-api.infomentor.se/…) och klistra in den
 * under Läxor & prov → Kalender. Länken är personlig – den sparas bara i Pluggis databas.
 */
@Service
public class CalendarSyncService {

    /**
     * {@code imported}: läxor/prov i kalendern som Pluggis har (nya + ändrade + oförändrade).
     * {@code created}/{@code updated}: hur många av dem som faktiskt var nya eller ändrades nu.
     */
    public record SyncResult(int read, int imported, int replanned, int skipped, int merged, int created, int updated) {
        public SyncResult(int read, int imported, int replanned, int skipped, int merged) {
            this(read, imported, replanned, skipped, merged, 0, 0);
        }
    }

    public record PreviewEntry(String uid, String summary, String description, LocalDate date,
                               Homework.Kind kind, boolean schoolwork, boolean alreadyImported, boolean inRange) {
    }

    public record Status(String url, boolean onlySchoolwork, Instant lastSync, SyncResult lastResult, String lastError) {
    }

    private static final Logger log = LoggerFactory.getLogger(CalendarSyncService.class);

    private final HomeworkService homework;
    private final PlanningService planning;
    private final SettingsService settings;
    private final PluggisProperties props;
    private final se.ostberg.pluggis.homework.MaterialService materials;
    private final RestClient http = RestClient.create();

    /** Senaste synk per elev. */
    private record LastSync(Instant at, SyncResult result, String error) {
    }

    private final java.util.Map<Long, LastSync> last = new java.util.concurrent.ConcurrentHashMap<>();
    private final se.ostberg.pluggis.student.StudentService students;

    public CalendarSyncService(HomeworkService homework, PlanningService planning, SettingsService settings,
                               PluggisProperties props, se.ostberg.pluggis.homework.MaterialService materials,
                               se.ostberg.pluggis.student.StudentService students) {
        this.students = students;
        this.materials = materials;
        this.homework = homework;
        this.planning = planning;
        this.settings = settings;
        this.props = props;
    }

    // ---- Prenumeration ----

    /** Aktuell elevs kalenderlänk; för första eleven även PLUGGIS_ICAL_URL. */
    public String subscriptionUrl() {
        var s = students.current();
        String url = blankToNull(s.getCalendarUrl());
        if (url == null && s.getId().equals(se.ostberg.pluggis.student.StudentContext.defaultId())) {
            url = props.calendar() == null ? null : blankToNull(props.calendar().icalUrl());
        }
        return url;
    }

    public boolean onlySchoolwork() {
        return students.current().isCalendarOnlySchoolwork();
    }

    public Status status() {
        LastSync l = last.get(students.current().getId());
        return new Status(subscriptionUrl(), onlySchoolwork(), l == null ? null : l.at(),
                l == null ? null : l.result(), l == null ? null : l.error());
    }

    public SyncResult subscribe(String url, boolean onlySchoolwork) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Ange en kalenderlänk");
        }
        String ics = fetch(url); // kontrollera att länken fungerar innan den sparas
        var s = students.current();
        s.setCalendarUrl(url.trim());
        s.setCalendarOnlySchoolwork(onlySchoolwork);
        students.save(s);
        return remember(importIcs(ics, null, onlySchoolwork));
    }

    public void unsubscribe() {
        var s = students.current();
        s.setCalendarUrl("");
        students.save(s);
    }

    /** Varje timme: synka varje elevs kalender, en elev i taget. */
    @Scheduled(cron = "${pluggis.calendar.sync-cron:0 5 * * * *}", zone = "${pluggis.timezone:Europe/Stockholm}")
    public void scheduledSync() {
        for (var student : students.all()) {
            se.ostberg.pluggis.student.StudentContext.run(student.getId(), () -> {
                if (subscriptionUrl() == null) {
                    return;
                }
                try {
                    SyncResult r = syncNow();
                    log.info("Kalendersynk elev {}: {} händelser i kalendern, {} läxor/prov ({} nya, {} ändrade), {} omplanerade, "
                                    + "{} överhoppade (annat än läxor/prov eller utanför datumen)",
                            student.getId(), r.read(), r.imported(), r.created(), r.updated(), r.replanned(), r.skipped());
                } catch (RuntimeException e) {
                    log.warn("Kalendersynk misslyckades för elev {}: {}", student.getId(), e.toString());
                }
            });
        }
    }

    public SyncResult syncNow() {
        String url = subscriptionUrl();
        if (url == null) {
            throw new IllegalArgumentException("Ingen kalender att synka – lägg till en länk först");
        }
        try {
            return remember(importIcs(fetch(url), null, onlySchoolwork()));
        } catch (RuntimeException e) {
            LastSync prev = last.get(students.current().getId());
            last.put(students.current().getId(), new LastSync(Instant.now(), prev == null ? null : prev.result(), e.getMessage()));
            throw e;
        }
    }

    public String fetch(String url) {
        String fixed = normalizeUrl(url);
        try {
            var response = http.get().uri(fixed).retrieve().toEntity(byte[].class);
            String ics = decode(response.getBody(), response.getHeaders().getContentType());
            if (ics == null || !ics.contains("BEGIN:VCALENDAR")) {
                throw new IllegalArgumentException("Länken gav ingen iCal-kalender");
            }
            return ics;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Kunde inte hämta kalendern: " + e.getMessage(), e);
        }
    }

    // ---- Fil/text ----

    /** Vad som skulle importeras – inget sparas. */
    public List<PreviewEntry> preview(String ics) {
        LocalDate today = LocalDate.now(props.zone());
        int horizon = horizon();
        List<PreviewEntry> out = new ArrayList<>();
        for (IcalParser.Entry e : IcalParser.parse(ics, props.zone())) {
            boolean inRange = !e.date().isBefore(today.minusDays(1)) && !e.date().isAfter(today.plusDays(horizon));
            out.add(new PreviewEntry(e.uid(), e.summary(), e.description(), e.date(), kind(e),
                    IcalParser.looksLikeSchoolwork(e.summary(), e.description()),
                    homework.findByExternalId(e.uid()).isPresent(), inRange));
        }
        out.sort((a, b) -> a.date().compareTo(b.date()));
        return out;
    }

    /**
     * Importerar posterna. {@code uids} = bara de valda (null = alla), {@code onlySchoolwork} = hoppa över
     * sådant som inte ser ut som läxor/prov. Poster långt bak i tiden eller för långt fram hoppas alltid över.
     */
    public synchronized SyncResult importIcs(String ics, Set<String> uids, boolean onlySchoolwork) {
        LocalDate today = LocalDate.now(props.zone());
        int horizon = horizon();
        var entries = IcalParser.parse(ics, props.zone());
        int imported = 0;
        int replanned = 0;
        int skipped = 0;
        int updated = 0;
        // InfoMentor kan byta UID mellan hämtningar; "nya" räknas därför som läxor som inte fanns före synken
        Set<Long> existedBefore = new HashSet<>();
        homework.all().forEach(x -> existedBefore.add(x.getId()));
        Set<String> seen = new HashSet<>();
        for (IcalParser.Entry e : entries) {
            if (!seen.add(TextFix.key(e.summary()) + "|" + e.date())) {
                skipped++; // samma händelse två gånger i samma kalender
                continue;
            }
            boolean selected = uids == null || uids.contains(e.uid());
            boolean outOfRange = e.date().isBefore(today.minusDays(1)) || e.date().isAfter(today.plusDays(horizon));
            boolean filteredOut = uids == null && onlySchoolwork && !IcalParser.looksLikeSchoolwork(e.summary(), e.description());
            if (!selected || outOfRange || filteredOut) {
                skipped++;
                continue;
            }
            var existing = homework.findByExternalId(e.uid());
            LocalDate before = existing.map(Homework::getDueDate).orElse(null);
            String fingerprint = existing.map(CalendarSyncService::fingerprint).orElse(null);
            Homework h = homework.upsertFromCalendar(e.uid(), IcalParser.subjectFrom(e.summary()), e.summary(),
                    e.description(), e.date(), kind(e));
            imported++;
            if (fingerprint != null && !fingerprint.equals(fingerprint(h))) {
                updated++;
            }
            if (before != null && !Objects.equals(before, e.date())) {
                try {
                    planning.replanIfPlanned(h.getId());
                    replanned++;
                } catch (RuntimeException ex) {
                    log.warn("Kunde inte planera om läxa {}: {}", h.getId(), ex.toString());
                }
            }
        }
        int merged = mergeDuplicates();
        int created = (int) homework.all().stream().filter(x -> !existedBefore.contains(x.getId())).count();
        return new SyncResult(entries.size(), imported, replanned, skipped, merged, created, updated);
    }

    private static String fingerprint(Homework h) {
        return h.getTitle() + "|" + h.getDescription() + "|" + h.getCalendarDate() + "|" + h.getKind() + "|" + h.getSubject();
    }

    /**
     * Slår ihop kalenderimporterade läxor med samma titel (oavsett åäö-fel) och datum.
     * Behåller den som har mest eget innehåll (plan, kortlek, delsteg, egen beskrivning)
     * och tar bort resten. Rättar också trasiga åäö i titlar. Returnerar antal borttagna.
     */
    public synchronized int mergeDuplicates() {
        Map<String, List<Homework>> groups = new LinkedHashMap<>();
        for (Homework h : homework.withDueDate()) {
            if (h.getExternalId() != null && h.getTitle() != null && !h.getTitle().equals(TextFix.fixMojibake(h.getTitle()))) {
                homework.fixTitleEncoding(h.getId());
            }
            String key = TextFix.key(h.getTitle());
            groups.computeIfAbsent((key.isEmpty() ? String.valueOf(h.getTitle()) : key) + "|" + h.getCalendarDate(), k -> new ArrayList<>()).add(h);
        }
        int removed = 0;
        for (List<Homework> group : groups.values()) {
            // Bara grupper där kalendern är inblandad – två egna läxor med samma namn rör vi inte.
            if (group.size() < 2 || group.stream().noneMatch(h -> h.getExternalId() != null)) {
                continue;
            }
            Homework keep = group.stream().max(Comparator.comparingInt(this::richness)
                    .thenComparing(Homework::getCreatedAt, Comparator.reverseOrder())).orElseThrow();
            String uid = keep.getExternalId();
            for (Homework dup : group) {
                if (dup == keep) {
                    continue;
                }
                if (uid == null && dup.getExternalId() != null) {
                    uid = dup.getExternalId();
                }
                if (keep.getDeckId() == null && dup.getDeckId() != null) {
                    homework.linkDeck(keep.getId(), dup.getDeckId());
                }
                planning.deleteForHomework(dup.getId());
                materials.deleteForHomework(dup.getId());
                homework.delete(dup.getId());
                removed++;
            }
            if (keep.getExternalId() == null && uid != null) {
                homework.adoptExternalId(keep.getId(), uid);
            }
        }
        if (removed > 0) {
            log.info("Kalender: slog ihop {} dubbletter", removed);
        }
        return removed;
    }

    private int richness(Homework h) {
        int score = 0;
        if (!planning.forHomework(h.getId()).isEmpty()) {
            score += 8;
        }
        if (h.getDeckId() != null) {
            score += 4;
        }
        if (!h.getSteps().isEmpty()) {
            score += 2;
        }
        if (h.getSource() != Homework.Source.CALENDAR) {
            score += 2; // beskrivningen är ifylld för hand
        }
        if (h.getStatus() == Homework.Status.DONE) {
            score += 1;
        }
        if (h.isOther() || h.getDueDate() == null) {
            score += 3; // ändrad för hand till annat / utan datum – det valet ska stå kvar
        }
        return score;
    }

    /** Bakåtkompatibelt: importera allt inom tidsfönstret. */
    public SyncResult importIcs(String ics) {
        return importIcs(ics, null, false);
    }

    private SyncResult remember(SyncResult r) {
        last.put(students.current().getId(), new LastSync(Instant.now(), r, null));
        return r;
    }

    /**
     * iCal är UTF-8 enligt RFC 5545. Servrar som InfoMentor anger ofta ingen charset, och då
     * skulle Spring tolka texten som ISO-8859-1 ("FÃ¶rmiddag"). Använd UTF-8 om inget annat anges.
     */
    static String decode(byte[] body, MediaType contentType) {
        if (body == null) {
            return null;
        }
        Charset charset = contentType != null && contentType.getCharset() != null
                ? contentType.getCharset() : StandardCharsets.UTF_8;
        String text = new String(body, charset);
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    /** webcal:// och webcals:// (InfoMentor m.fl.) är https:// i praktiken. */
    static String normalizeUrl(String url) {
        String u = url == null ? "" : url.trim();
        u = u.replaceFirst("(?i)^webcals?://", "https://");
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            throw new IllegalArgumentException("Länken måste börja med webcal://, webcals://, https:// eller http://");
        }
        return u;
    }

    private static Homework.Kind kind(IcalParser.Entry e) {
        return IcalParser.looksLikeTest(e.summary(), e.description()) ? Homework.Kind.TEST : Homework.Kind.HOMEWORK;
    }

    private int horizon() {
        return props.calendar() == null || props.calendar().horizonDays() <= 0 ? 60 : props.calendar().horizonDays();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
