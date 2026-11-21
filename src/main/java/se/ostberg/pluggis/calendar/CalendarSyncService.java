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

import java.time.LocalDate;
import java.util.Objects;

/**
 * Importerar läxor och prov från en iCal-kalender (om skolan har en att prenumerera på)
 * eller från en uppladdad .ics-fil. Samma händelse (UID) uppdateras i stället för att dubbleras,
 * och flyttas ett datum görs planen om.
 *
 * InfoMentor har (såvitt vi vet) ingen iCal-export – där används skärmdump/text-import
 * (/api/homework/photo och /api/homework/text) i stället.
 */
@Service
public class CalendarSyncService {

    public record SyncResult(int read, int imported, int replanned) {
    }

    private static final Logger log = LoggerFactory.getLogger(CalendarSyncService.class);

    private final HomeworkService homework;
    private final PlanningService planning;
    private final PluggisProperties props;
    private final RestClient http = RestClient.create();

    public CalendarSyncService(HomeworkService homework, PlanningService planning, PluggisProperties props) {
        this.homework = homework;
        this.planning = planning;
        this.props = props;
    }

    @Scheduled(cron = "${pluggis.calendar.sync-cron:0 5 * * * *}", zone = "${pluggis.timezone:Europe/Stockholm}")
    public void scheduledSync() {
        String url = props.calendar() == null ? null : props.calendar().icalUrl();
        if (url == null || url.isBlank()) {
            return;
        }
        try {
            SyncResult r = syncFromUrl(url);
            log.info("Kalendersynk: {} lästa, {} importerade, {} omplanerade", r.read(), r.imported(), r.replanned());
        } catch (RuntimeException e) {
            log.warn("Kalendersynk misslyckades: {}", e.toString());
        }
    }

    public SyncResult syncFromUrl(String url) {
        String fixed = url.trim().replaceFirst("^webcal://", "https://");
        String ics = http.get().uri(fixed).retrieve().body(String.class);
        return importIcs(ics);
    }

    public SyncResult importIcs(String ics) {
        LocalDate today = LocalDate.now(props.zone());
        int horizon = props.calendar() == null || props.calendar().horizonDays() <= 0 ? 60 : props.calendar().horizonDays();
        var entries = IcalParser.parse(ics, props.zone());
        int imported = 0;
        int replanned = 0;
        for (IcalParser.Entry e : entries) {
            if (e.date().isBefore(today.minusDays(1)) || e.date().isAfter(today.plusDays(horizon))) {
                continue;
            }
            Homework.Kind kind = IcalParser.looksLikeTest(e.summary(), e.description()) ? Homework.Kind.TEST : Homework.Kind.HOMEWORK;
            LocalDate before = homework.findByExternalId(e.uid()).map(Homework::getDueDate).orElse(null);
            Homework h = homework.upsertFromCalendar(e.uid(), IcalParser.subjectFrom(e.summary()), e.summary(),
                    e.description(), e.date(), kind);
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
        return new SyncResult(entries.size(), imported, replanned);
    }
}
