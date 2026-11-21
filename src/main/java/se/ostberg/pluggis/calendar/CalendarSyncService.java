package se.ostberg.pluggis.calendar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import se.ostberg.pluggis.config.PluggisProperties;
import se.ostberg.pluggis.homework.Homework;
import se.ostberg.pluggis.homework.HomeworkService;
import se.ostberg.pluggis.planning.PlanningService;
import se.ostberg.pluggis.settings.SettingsService;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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

    public record SyncResult(int read, int imported, int replanned, int skipped) {
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
    private final RestClient http = RestClient.create();

    private volatile Instant lastSync;
    private volatile SyncResult lastResult;
    private volatile String lastError;

    public CalendarSyncService(HomeworkService homework, PlanningService planning, SettingsService settings,
                               PluggisProperties props) {
        this.homework = homework;
        this.planning = planning;
        this.settings = settings;
        this.props = props;
    }

    // ---- Prenumeration ----

    /** Sparad länk i webben, annars PLUGGIS_ICAL_URL. */
    public String subscriptionUrl() {
        return settings.get(SettingsService.CALENDAR_URL)
                .orElse(props.calendar() == null ? null : blankToNull(props.calendar().icalUrl()));
    }

    public boolean onlySchoolwork() {
        return settings.get(SettingsService.CALENDAR_ONLY_SCHOOLWORK).map(Boolean::parseBoolean).orElse(true);
    }

    public Status status() {
        return new Status(subscriptionUrl(), onlySchoolwork(), lastSync, lastResult, lastError);
    }

    public SyncResult subscribe(String url, boolean onlySchoolwork) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Ange en kalenderlänk");
        }
        String ics = fetch(url); // kontrollera att länken fungerar innan den sparas
        settings.put(SettingsService.CALENDAR_URL, url.trim());
        settings.put(SettingsService.CALENDAR_ONLY_SCHOOLWORK, String.valueOf(onlySchoolwork));
        return remember(importIcs(ics, null, onlySchoolwork));
    }

    public void unsubscribe() {
        settings.put(SettingsService.CALENDAR_URL, "");
    }

    @Scheduled(cron = "${pluggis.calendar.sync-cron:0 5 * * * *}", zone = "${pluggis.timezone:Europe/Stockholm}")
    public void scheduledSync() {
        String url = subscriptionUrl();
        if (url == null) {
            return;
        }
        try {
            SyncResult r = syncNow();
            log.info("Kalendersynk: {} lästa, {} importerade, {} omplanerade, {} överhoppade",
                    r.read(), r.imported(), r.replanned(), r.skipped());
        } catch (RuntimeException e) {
            log.warn("Kalendersynk misslyckades: {}", e.toString());
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
            lastSync = Instant.now();
            lastError = e.getMessage();
            throw e;
        }
    }

    public String fetch(String url) {
        String fixed = normalizeUrl(url);
        try {
            String ics = http.get().uri(fixed).retrieve().body(String.class);
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
    public SyncResult importIcs(String ics, Set<String> uids, boolean onlySchoolwork) {
        LocalDate today = LocalDate.now(props.zone());
        int horizon = horizon();
        var entries = IcalParser.parse(ics, props.zone());
        int imported = 0;
        int replanned = 0;
        int skipped = 0;
        for (IcalParser.Entry e : entries) {
            boolean selected = uids == null || uids.contains(e.uid());
            boolean outOfRange = e.date().isBefore(today.minusDays(1)) || e.date().isAfter(today.plusDays(horizon));
            boolean filteredOut = uids == null && onlySchoolwork && !IcalParser.looksLikeSchoolwork(e.summary(), e.description());
            if (!selected || outOfRange || filteredOut) {
                skipped++;
                continue;
            }
            LocalDate before = homework.findByExternalId(e.uid()).map(Homework::getDueDate).orElse(null);
            Homework h = homework.upsertFromCalendar(e.uid(), IcalParser.subjectFrom(e.summary()), e.summary(),
                    e.description(), e.date(), kind(e));
            imported++;
            if (before != null && !Objects.equals(before, e.date())) {
                try {
                    planning.replanIfPlanned(h.getId());
                    replanned++;
                } catch (RuntimeException ex) {
                    log.warn("Kunde inte planera om läxa {}: {}", h.getId(), ex.toString());
                }
            }
        }
        return new SyncResult(entries.size(), imported, replanned, skipped);
    }

    /** Bakåtkompatibelt: importera allt inom tidsfönstret. */
    public SyncResult importIcs(String ics) {
        return importIcs(ics, null, false);
    }

    private SyncResult remember(SyncResult r) {
        lastSync = Instant.now();
        lastResult = r;
        lastError = null;
        return r;
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
