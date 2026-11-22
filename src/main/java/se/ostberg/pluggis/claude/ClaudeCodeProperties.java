package se.ostberg.pluggis.claude;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Valfritt: kör Claude via det officiella {@code claude}-programmet (Claude Code, {@code claude -p})
 * i stället för direkt mot API:et. Då dras användningen från ditt Claude-abonnemang
 * (inloggning med {@code CLAUDE_CODE_OAUTH_TOKEN} från {@code claude setup-token}).
 *
 * @param backend       {@code api} (standard) eller {@code claude-code}
 * @param command       programmet att köra, normalt {@code claude}
 * @param timeoutSeconds längsta tid per anrop
 * @param maxConcurrent  högst så många samtidiga {@code claude}-processer
 */
@ConfigurationProperties("pluggis.claude")
public record ClaudeCodeProperties(String backend, String command, int timeoutSeconds, int maxConcurrent) {

    public boolean enabled() {
        return backend != null && backend.trim().equalsIgnoreCase("claude-code");
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
