package se.ostberg.pluggis.config;

import org.springframework.stereotype.Component;
import se.ostberg.pluggis.settings.SettingsService;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Inloggning till föräldravyn med lösenord i stället för token.
 *
 * <ul>
 *   <li>Lösenordet sparas som PBKDF2-hash (salt, 210 000 varv) – aldrig i klartext.</li>
 *   <li>Inloggad = en signerad cookie (giltig till och med, HMAC med en hemlighet som bara servern har) i 30 dagar.
 *       "Logga ut på alla enheter" byter hemligheten, så att alla cookies slutar gälla.</li>
 *   <li>Första lösenordet (eller ett nytt om det glömts) sätts med PLUGGIS_ADMIN_PASSWORD i .env vid start.</li>
 * </ul>
 */
@Component
public class AdminAuth {

    public static final String COOKIE = "pluggis_admin";
    public static final Duration TTL = Duration.ofDays(30);
    public static final int MIN_LENGTH = 8;

    static final String PASSWORD_KEY = "admin.password";
    static final String SECRET_KEY = "admin.secret";
    private static final int ITERATIONS = 210_000;

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AdminAuth.class);

    private final SettingsService settings;
    private final SecureRandom random = new SecureRandom();

    public AdminAuth(SettingsService settings) {
        this.settings = settings;
    }

    /** Vid start: PLUGGIS_ADMIN_PASSWORD i .env blir lösenordet (om det skiljer sig från det sparade). */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void fromEnvironment(org.springframework.boot.context.event.ApplicationReadyEvent event) {
        String pw = event.getApplicationContext().getEnvironment().getProperty("pluggis.admin-password", "");
        if (!pw.isBlank() && !check(pw)) {
            setPassword(pw);
            log.info("Lösenord för föräldravyn satt från PLUGGIS_ADMIN_PASSWORD (raden i .env kan tas bort nu)");
        } else if (!passwordSet()) {
            log.warn("Inget lösenord för föräldravyn – sätt PLUGGIS_ADMIN_PASSWORD i .env och starta om");
        }
    }

    public boolean passwordSet() {
        return settings.get(PASSWORD_KEY).isPresent();
    }

    /** Sätter ett nytt lösenord (minst {@value #MIN_LENGTH} tecken). */
    public void setPassword(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw new IllegalArgumentException("Lösenordet ska vara minst " + MIN_LENGTH + " tecken");
        }
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        settings.put(PASSWORD_KEY, "pbkdf2$" + ITERATIONS + "$" + b64(salt) + "$" + b64(hash(password, salt, ITERATIONS)));
    }

    /** Stämmer lösenordet? Jämförs på konstant tid. */
    public boolean check(String password) {
        if (password == null) {
            return false;
        }
        return settings.get(PASSWORD_KEY).map(stored -> {
            String[] p = stored.split("\\$");
            if (p.length != 4 || !p[0].equals("pbkdf2")) {
                return false;
            }
            byte[] expected = Base64.getDecoder().decode(p[3]);
            byte[] actual = hash(password, Base64.getDecoder().decode(p[2]), Integer.parseInt(p[1]));
            return MessageDigest.isEqual(expected, actual);
        }).orElse(false);
    }

    /** Ny inloggning: "giltig-till.signatur". */
    public String newSession() {
        long until = Instant.now().plus(TTL).getEpochSecond();
        return until + "." + sign(Long.toString(until));
    }

    /** Är cookien en giltig inloggning (rätt signatur och inte för gammal)? */
    public boolean valid(String session) {
        if (session == null || !passwordSet()) {
            return false;
        }
        int dot = session.indexOf('.');
        if (dot <= 0) {
            return false;
        }
        try {
            long until = Long.parseLong(session.substring(0, dot));
            if (until < Instant.now().getEpochSecond()) {
                return false;
            }
            byte[] expected = sign(session.substring(0, dot)).getBytes(StandardCharsets.US_ASCII);
            return MessageDigest.isEqual(expected, session.substring(dot + 1).getBytes(StandardCharsets.US_ASCII));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Ur en Cookie-header (WebSocket har ingen servlet-request med färdiga cookies). */
    public boolean validCookieHeader(String header) {
        if (header == null) {
            return false;
        }
        for (String part : header.split(";")) {
            String c = part.trim();
            if (c.startsWith(COOKIE + "=") && valid(c.substring(COOKIE.length() + 1))) {
                return true;
            }
        }
        return false;
    }

    /** Alla inloggningar slutar gälla (ny hemlighet). */
    public void logoutEverywhere() {
        byte[] s = new byte[32];
        random.nextBytes(s);
        settings.put(SECRET_KEY, b64(s));
    }

    private String sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret(), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] secret() {
        return Base64.getDecoder().decode(settings.get(SECRET_KEY).orElseGet(() -> {
            logoutEverywhere();
            return settings.get(SECRET_KEY).orElseThrow();
        }));
    }

    private static byte[] hash(String password, byte[] salt, int iterations) {
        try {
            var spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }
}
