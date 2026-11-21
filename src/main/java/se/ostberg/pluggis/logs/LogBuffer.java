package se.ostberg.pluggis.logs;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Håller de senaste loggraderna i minnet så att webbgränssnittet kan visa dem.
 * Kopplas på rot-loggern vid start; påverkar inte vanlig loggning till konsolen.
 */
@Component
public class LogBuffer extends AppenderBase<ILoggingEvent> {

    public static final int CAPACITY = 2000;

    public record LogLine(long seq, Instant time, String level, String logger, String thread, String message) {
    }

    private final ArrayDeque<LogLine> lines = new ArrayDeque<>(CAPACITY);
    private long seq;

    @EventListener(ApplicationStartedEvent.class)
    public void attach() {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
            return;
        }
        setContext(context);
        setName("pluggis-web-log");
        start();
        Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        if (root.getAppender(getName()) == null) {
            root.addAppender(this);
        }
    }

    @Override
    protected void append(ILoggingEvent event) {
        add(event.getLevel().toString(), event.getLoggerName(), event.getThreadName(),
                event.getFormattedMessage() + throwable(event.getThrowableProxy()), Instant.ofEpochMilli(event.getTimeStamp()));
    }

    synchronized void add(String level, String logger, String thread, String message, Instant time) {
        if (lines.size() >= CAPACITY) {
            lines.removeFirst();
        }
        lines.addLast(new LogLine(++seq, time, level, shorten(logger), thread, message));
    }

    /**
     * Rader efter {@code afterSeq} med minst {@code minLevel}, ev. filtrerat på text.
     * Högst {@code limit} rader (de senaste).
     */
    public synchronized List<LogLine> since(long afterSeq, String minLevel, String contains, int limit) {
        Level min = Level.toLevel(minLevel == null ? "DEBUG" : minLevel, Level.DEBUG);
        String needle = contains == null || contains.isBlank() ? null : contains.toLowerCase(Locale.ROOT);
        List<LogLine> out = new ArrayList<>();
        for (LogLine l : lines) {
            if (l.seq() <= afterSeq || !Level.toLevel(l.level()).isGreaterOrEqual(min)) {
                continue;
            }
            if (needle != null && !(l.message() + " " + l.logger()).toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            out.add(l);
        }
        int max = Math.max(1, Math.min(limit, CAPACITY));
        return out.size() > max ? out.subList(out.size() - max, out.size()) : out;
    }

    public synchronized long lastSeq() {
        return seq;
    }

    /** se.ostberg.pluggis.coach.CoachService → s.o.p.coach.CoachService */
    static String shorten(String logger) {
        if (logger == null) {
            return "";
        }
        String[] parts = logger.split("\\.");
        if (parts.length <= 2) {
            return logger;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length - 2; i++) {
            sb.append(parts[i].isEmpty() ? "" : parts[i].charAt(0)).append('.');
        }
        return sb.append(parts[parts.length - 2]).append('.').append(parts[parts.length - 1]).toString();
    }

    private static String throwable(IThrowableProxy t) {
        if (t == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n").append(t.getClassName()).append(": ").append(t.getMessage());
        StackTraceElementProxy[] frames = t.getStackTraceElementProxyArray();
        for (int i = 0; frames != null && i < Math.min(8, frames.length); i++) {
            sb.append("\n    at ").append(frames[i].getSTEAsString());
        }
        if (t.getCause() != null) {
            sb.append("\nCaused by: ").append(t.getCause().getClassName()).append(": ").append(t.getCause().getMessage());
        }
        return sb.toString();
    }
}
