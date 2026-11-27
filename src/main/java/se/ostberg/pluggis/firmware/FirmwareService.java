package se.ostberg.pluggis.firmware;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Firmware till Tab5 som kan installeras över WiFi. CI bygger firmware.bin på main och lägger den i GitHub-releasen
 * {@code tab5} tillsammans med version.txt. Servern hämtar den när någon letar efter en uppdatering (Tab5 eller
 * föräldravyn) – eller tar emot en egen fil från föräldravyn – och sparar den under data/firmware.
 * Tab5 laddar ner den från servern, aldrig direkt från internet.
 */
@Service
public class FirmwareService {

    private static final Logger log = LoggerFactory.getLogger(FirmwareService.class);
    static final long MAX_SIZE = 6_500_000;   // en app-partition i default_16MB.csv (6,25 MB)
    private static final Duration MIN_CHECK_INTERVAL = Duration.ofSeconds(30);

    public record Info(String version, long size, String md5, String sha256, Instant savedAt, String source) {
    }

    private final Path dir;
    private final String repo;
    private final String tag;
    private final String githubToken;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private volatile Instant lastCheck;
    private volatile String lastError;

    public FirmwareService(@Value("${pluggis.firmware.dir:./data/firmware}") String dir,
                           @Value("${pluggis.firmware.repo:ostbergjohan/pluggis}") String repo,
                           @Value("${pluggis.firmware.tag:tab5}") String tag,
                           @Value("${pluggis.firmware.github-token:}") String githubToken,
                           ObjectMapper mapper) {
        this.dir = Path.of(dir);
        this.repo = repo;
        this.tag = tag;
        this.githubToken = githubToken == null ? "" : githubToken.trim();
        this.mapper = mapper;
    }

    /** Den firmware som finns på servern just nu. */
    public Optional<Info> current() {
        Path meta = dir.resolve("firmware.json");
        if (!Files.exists(meta) || !Files.exists(dir.resolve("firmware.bin"))) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(meta.toFile(), Info.class));
        } catch (IOException e) {
            log.warn("Kunde inte läsa {}: {}", meta, e.getMessage());
            return Optional.empty();
        }
    }

    public byte[] binary() throws IOException {
        return Files.readAllBytes(dir.resolve("firmware.bin"));
    }

    /** Status för Tab5 och föräldravyn. */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        Optional<Info> c = current();
        m.put("available", c.isPresent());
        c.ifPresent(i -> {
            m.put("version", i.version());
            m.put("size", i.size());
            m.put("md5", i.md5());
            m.put("savedAt", i.savedAt().toString());
            m.put("source", i.source());
        });
        m.put("repo", repo + " (release " + tag + ")");
        m.put("lastCheck", lastCheck == null ? null : lastCheck.toString());
        m.put("lastError", lastError);
        return m;
    }

    /**
     * Letar efter en ny version i GitHub-releasen och hämtar den om den skiljer sig från den som finns.
     * Högst en gång per halvminut (annars svaras med det som redan finns).
     */
    public synchronized Map<String, Object> check() {
        Instant now = Instant.now();
        if (lastCheck != null && lastCheck.plus(MIN_CHECK_INTERVAL).isAfter(now)) {
            return status();
        }
        lastCheck = now;
        try {
            JsonNode release = getJson("https://api.github.com/repos/" + repo + "/releases/tags/" + tag);
            JsonNode bin = asset(release, "firmware.bin");
            JsonNode ver = asset(release, "version.txt");
            if (bin == null || ver == null) {
                throw new IOException("releasen " + tag + " saknar firmware.bin eller version.txt");
            }
            String version = new String(download(ver), StandardCharsets.UTF_8).trim();
            if (version.isEmpty() || version.length() > 40) {
                throw new IOException("konstig version.txt");
            }
            if (current().map(Info::version).filter(version::equals).isPresent()) {
                lastError = null;
                return status();
            }
            save(download(bin), version, "GitHub");
            lastError = null;
            log.info("Ny Tab5-firmware från GitHub: {}", version);
        } catch (IOException | RuntimeException e) {
            lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("Kunde inte hämta Tab5-firmware från GitHub: {}", lastError);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            lastError = "avbruten";
        }
        return status();
    }

    /** Sparar en firmware (från GitHub eller uppladdad i föräldravyn). */
    public synchronized Info save(byte[] bin, String version, String source) throws IOException {
        validate(bin);
        String v = version == null || version.isBlank() ? "egen-" + Instant.now().toString().substring(0, 16).replace(':', '.') : version.trim();
        if (v.length() > 40) {
            throw new IllegalArgumentException("Versionen får vara högst 40 tecken");
        }
        Files.createDirectories(dir);
        Path tmp = dir.resolve("firmware.bin.tmp");
        Files.write(tmp, bin);
        Files.move(tmp, dir.resolve("firmware.bin"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Info info = new Info(v, bin.length, hex("MD5", bin), hex("SHA-256", bin), Instant.now(), source);
        mapper.writeValue(dir.resolve("firmware.json").toFile(), info);
        return info;
    }

    /** En ESP32-app börjar med 0xE9 och får plats i en app-partition. */
    static void validate(byte[] bin) {
        if (bin == null || bin.length < 1024) {
            throw new IllegalArgumentException("Filen är för liten för att vara en firmware");
        }
        if (bin.length > MAX_SIZE) {
            throw new IllegalArgumentException("Filen är för stor (högst " + MAX_SIZE / 1_000_000.0 + " MB)");
        }
        if ((bin[0] & 0xFF) != 0xE9) {
            throw new IllegalArgumentException("Det här är ingen ESP32-firmware (firmware.bin från pio run)");
        }
    }

    private static JsonNode asset(JsonNode release, String name) {
        for (JsonNode a : release.path("assets")) {
            if (name.equals(a.path("name").asText())) {
                return a;
            }
        }
        return null;
    }

    private JsonNode getJson(String url) throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(request(url).header("Accept", "application/vnd.github+json").build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 404) {
            throw new IOException("hittar ingen release " + tag + " i " + repo + " (är repot privat? sätt PLUGGIS_GITHUB_TOKEN)");
        }
        if (r.statusCode() != 200) {
            throw new IOException("GitHub svarade " + r.statusCode());
        }
        return mapper.readTree(r.body());
    }

    /** Hämtar en fil i releasen via API:t (fungerar även för privata repon med token). */
    private byte[] download(JsonNode asset) throws IOException, InterruptedException {
        HttpResponse<byte[]> r = http.send(request(asset.path("url").asText())
                .header("Accept", "application/octet-stream").timeout(Duration.ofMinutes(3)).build(), HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() != 200) {
            throw new IOException("kunde inte hämta " + asset.path("name").asText() + " (" + r.statusCode() + ")");
        }
        return r.body();
    }

    private HttpRequest.Builder request(String url) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("User-Agent", "pluggis").header("X-GitHub-Api-Version", "2022-11-28");
        if (!githubToken.isEmpty()) {
            b.header("Authorization", "Bearer " + githubToken);
        }
        return b;
    }

    private static String hex(String algorithm, byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
