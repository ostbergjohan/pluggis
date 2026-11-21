package se.ostberg.pluggis.settings;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class SettingsService {

    public static final String VOICE = "speech.voice";
    public static final String SPEED = "speech.speed";
    public static final String CALENDAR_URL = "calendar.url";
    public static final String CALENDAR_ONLY_SCHOOLWORK = "calendar.onlySchoolwork";

    private final AppSettingRepository repo;

    public SettingsService(AppSettingRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public Optional<String> get(String key) {
        return repo.findById(key).map(AppSetting::getValue).filter(v -> !v.isBlank());
    }

    @Transactional
    public void put(String key, String value) {
        repo.findById(key).ifPresentOrElse(s -> s.set(value), () -> repo.save(new AppSetting(key, value)));
    }

    /** Vald röst, eller null för ljudtjänstens standard. */
    public String voice() {
        return get(VOICE).orElse(null);
    }

    /** Taltempo 0,7–1,4 där 1,0 är normalt. */
    public double speed() {
        return get(SPEED).map(v -> {
            try {
                return Double.parseDouble(v);
            } catch (NumberFormatException e) {
                return 1.0;
            }
        }).map(SettingsService::clampSpeed).orElse(1.0);
    }

    public static double clampSpeed(double speed) {
        return Math.max(0.7, Math.min(1.4, speed));
    }

    /** Piper räknar tvärtom: length_scale > 1 betyder långsammare. */
    public static double lengthScale(double speed) {
        return Math.round(1000.0 / clampSpeed(speed)) / 1000.0;
    }
}
