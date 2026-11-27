package se.ostberg.pluggis.backup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import se.ostberg.pluggis.settings.SettingsService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Säkerhetskopior av databasen (läxor, planer, material, poäng, elever, inställningar). H2 kopieras medan Pluggis
 * kör ({@code BACKUP TO}, en zip-fil) till data/backups – varje natt och när föräldern trycker på knappen.
 * De senaste {@code keep} sparas. Med Postgres görs inga kopior här (använd pg_dump).
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    static final Pattern NAME = Pattern.compile("pluggis-\\d{4}-\\d{2}-\\d{2}-\\d{4}(-\\d+)?\\.zip");
    static final String NIGHTLY = "backup.nightly";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm");

    public record Backup(String name, long size, Instant createdAt) {
    }

    private final JdbcTemplate jdbc;
    private final SettingsService settings;
    private final Path dir;
    private final int keep;
    private final boolean h2;
    private final ZoneId zone;

    public BackupService(JdbcTemplate jdbc, SettingsService settings,
                         @Value("${pluggis.backup.dir:./data/backups}") String dir,
                         @Value("${pluggis.backup.keep:14}") int keep,
                         @Value("${spring.datasource.url:}") String url,
                         @Value("${pluggis.timezone:Europe/Stockholm}") String zone) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.dir = Path.of(dir);
        this.keep = Math.max(1, keep);
        this.h2 = url.startsWith("jdbc:h2:");
        this.zone = ZoneId.of(zone);
    }

    public boolean supported() {
        return h2;
    }

    public boolean nightly() {
        return settings.get(NIGHTLY).map(Boolean::parseBoolean).orElse(true);
    }

    public void setNightly(boolean on) {
        settings.put(NIGHTLY, String.valueOf(on));
    }

    public int keep() {
        return keep;
    }

    public Path dir() {
        return dir;
    }

    public List<Backup> list() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> NAME.matcher(p.getFileName().toString()).matches())
                    .map(p -> {
                        try {
                            return new Backup(p.getFileName().toString(), Files.size(p), Files.getLastModifiedTime(p).toInstant());
                        } catch (IOException e) {
                            return null;
                        }
                    })
                    .filter(java.util.Objects::nonNull)
                    .sorted(Comparator.comparing(Backup::createdAt).reversed())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Gör en kopia nu och rensar bort de äldsta. */
    public synchronized Backup create() throws IOException {
        if (!h2) {
            throw new IllegalStateException("Säkerhetskopior görs bara med den inbyggda databasen (H2). Med Postgres: pg_dump.");
        }
        Files.createDirectories(dir);
        String base = "pluggis-" + LocalDateTime.now(zone).format(STAMP);
        Path file = dir.resolve(base + ".zip");
        for (int i = 2; Files.exists(file); i++) {
            file = dir.resolve(base + "-" + i + ".zip");
        }
        // H2 kopierar databasen konsekvent medan den används
        jdbc.execute("BACKUP TO '" + file.toAbsolutePath().toString().replace("'", "''") + "'");
        prune();
        Backup b = new Backup(file.getFileName().toString(), Files.size(file), Instant.now());
        log.info("Säkerhetskopia gjord: {} ({} kB)", b.name(), b.size() / 1024);
        return b;
    }

    /** Filen för nedladdning, eller null om namnet inte är en av våra kopior. */
    public Path file(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            return null;
        }
        Path p = dir.resolve(name);
        return Files.isRegularFile(p) ? p : null;
    }

    public boolean delete(String name) throws IOException {
        Path p = file(name);
        return p != null && Files.deleteIfExists(p);
    }

    void prune() throws IOException {
        List<Backup> all = list();
        for (int i = keep; i < all.size(); i++) {
            Files.deleteIfExists(dir.resolve(all.get(i).name()));
        }
    }

    @Scheduled(cron = "${pluggis.backup.cron:0 15 3 * * *}", zone = "${pluggis.timezone:Europe/Stockholm}")
    public void nightlyBackup() {
        if (!h2 || !nightly()) {
            return;
        }
        try {
            create();
        } catch (IOException | RuntimeException e) {
            log.warn("Nattens säkerhetskopia misslyckades: {}", e.getMessage());
        }
    }
}
