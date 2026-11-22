package se.ostberg.pluggis.student;

import java.util.function.Supplier;

/**
 * Vilken elev det aktuella anropet gäller. Sätts av API-filtret (förälderns elevval eller elevens kod),
 * av WebSocket-hanteraren (enhetens elev) och av schemalagda jobb (en elev i taget).
 * Hibernate läser den via {@link StudentTenantResolver} och filtrerar läxor, plan, material och
 * flashkort automatiskt.
 */
public final class StudentContext {

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();
    private static volatile Long defaultId = 1L;

    private StudentContext() {
    }

    public static Long get() {
        return CURRENT.get();
    }

    /** Aktuell elev, eller förvald elev om inget är satt. */
    public static Long currentOrDefault() {
        Long id = CURRENT.get();
        return id != null ? id : defaultId;
    }

    public static void set(Long id) {
        if (id == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(id);
        }
    }

    public static void clear() {
        CURRENT.remove();
    }

    static void setDefault(Long id) {
        if (id != null) {
            defaultId = id;
        }
    }

    public static Long defaultId() {
        return defaultId;
    }

    public static void run(Long id, Runnable task) {
        call(id, () -> {
            task.run();
            return null;
        });
    }

    public static <T> T call(Long id, Supplier<T> task) {
        Long before = CURRENT.get();
        set(id);
        try {
            return task.get();
        } finally {
            set(before);
        }
    }

    /** För trådar: ta med aktuell elev in i en uppgift som körs någon annanstans. */
    public static Runnable wrap(Runnable task) {
        Long id = currentOrDefault();
        return () -> run(id, task);
    }
}
