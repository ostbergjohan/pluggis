package se.ostberg.pluggis.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.time.Instant;

/** Inställningar som föräldern ändrar i webben (t.ex. röst och tempo). */
@Entity
public class AppSetting {

    @Id
    @Column(name = "setting_key", length = 100)
    private String key;

    @Column(name = "setting_value", length = 2000)
    private String value;

    private Instant updatedAt;

    protected AppSetting() {
    }

    public AppSetting(String key, String value) {
        this.key = key;
        set(value);
    }

    public void set(String value) {
        this.value = value;
        this.updatedAt = Instant.now();
    }

    public String getKey() { return key; }
    public String getValue() { return value; }
    public Instant getUpdatedAt() { return updatedAt; }
}
