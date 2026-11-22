package se.ostberg.pluggis.claude;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Valfritt: kör Claude via det officiella {@code claude}-programmet (Claude Code, {@code claude -p})
 * i stället för direkt mot API:et. Då dras användningen från ditt Claude-abonnemang
 * (inloggning med {@code CLAUDE_CODE_OAUTH_TOKEN} från {@code claude setup-token}).
 *
 * @param backend       {@code api} (standard), {@code claude-code} (allt via abonnemanget) eller
 *                      {@code hybrid} (jobben i {@code apiPurposes} via API-nyckel, resten via abonnemanget)
 * @param apiPurposes   i hybridläget: jobb som går via API-nyckel, standard {@code coach} (rösten – snabbast)
 * @param command       programmet att köra, normalt {@code claude}
 * @param timeoutSeconds längsta tid per anrop
 * @param maxConcurrent  högst så många samtidiga {@code claude}-processer
 */
@ConfigurationProperties("pluggis.claude")
public record ClaudeCodeProperties(String backend, String command, int timeoutSeconds, int maxConcurrent,
                                   java.util.List<String> apiPurposes) {

    /** Två konstruktorer: tala om för Spring vilken som binder konfigurationen. */
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public ClaudeCodeProperties {
    }

    public ClaudeCodeProperties(String backend, String command, int timeoutSeconds, int maxConcurrent) {
        this(backend, command, timeoutSeconds, maxConcurrent, null);
    }

    public boolean enabled() {
        return backend != null && (mode().equals("claude-code") || mode().equals("hybrid"));
    }

    public String mode() {
        return backend == null ? "api" : backend.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** Ska det här jobbet köras via Claude Code? "flashcards-vision" räknas som "flashcards". */
    public boolean useFor(String purpose) {
        if (!enabled()) {
            return false;
        }
        if (mode().equals("claude-code")) {
            return true;
        }
        java.util.List<String> api = apiPurposes == null || apiPurposes.isEmpty() ? java.util.List.of("coach") : apiPurposes;
        String p = purpose == null ? "" : purpose;
        return api.stream().map(String::trim).noneMatch(a -> p.equals(a) || p.startsWith(a + "-"));
    }

    public String commandOrDefault() {
        return command == null || command.isBlank() ? "claude" : command.trim();
    }

    public int timeout() {
        return timeoutSeconds > 0 ? timeoutSeconds : 120;
    }

    public int concurrency() {
        return maxConcurrent > 0 ? maxConcurrent : 2;
    }
}
