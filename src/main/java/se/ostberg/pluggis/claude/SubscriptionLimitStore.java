package se.ostberg.pluggis.claude;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import se.ostberg.pluggis.settings.SettingsService;

import java.util.Map;

/** Sparar abonnemangets läge (så att föräldravyn visar det även efter en omstart) och loggar när det ändras. */
@Component
public class SubscriptionLimitStore {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionLimitStore.class);
    static final String KEY = "claude.subscription";

    private final SettingsService settings;
    private final ObjectMapper mapper;

    public SubscriptionLimitStore(SettingsService settings, ObjectMapper mapper) {
        this.settings = settings;
        this.mapper = mapper;
    }

    @PostConstruct
    void start() {
        settings.get(KEY).ifPresent(json -> {
            try {
                SubscriptionLimit.restore(mapper.readValue(json, new TypeReference<Map<String, Object>>() { }));
            } catch (Exception e) {
                log.debug("Kunde inte läsa sparat abonnemangsläge: {}", e.toString());
            }
        });
        SubscriptionLimit.onChange(m -> {
            log.info("Claude-abonnemanget: {} ({}){}{}", m.get("status"), m.get("type") == null ? "gräns okänd" : m.get("type"),
                    m.get("utilization") == null ? "" : ", " + Math.round(((Number) m.get("utilization")).doubleValue() * 100) + " % använt",
                    m.get("resetsAt") == null ? "" : ", släpper " + m.get("resetsAt"));
            try {
                settings.put(KEY, mapper.writeValueAsString(m));
            } catch (Exception e) {
                log.debug("Kunde inte spara abonnemangsläge: {}", e.toString());
            }
        });
    }
}
