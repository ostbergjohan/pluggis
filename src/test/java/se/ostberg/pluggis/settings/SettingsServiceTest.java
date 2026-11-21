package se.ostberg.pluggis.settings;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SettingsServiceTest {

    @Test
    void speedMapsToInversePiperLengthScaleAndIsClamped() {
        assertThat(SettingsService.lengthScale(1.0)).isEqualTo(1.0);
        assertThat(SettingsService.lengthScale(1.25)).isEqualTo(0.8);
        assertThat(SettingsService.lengthScale(0.8)).isEqualTo(1.25);
        assertThat(SettingsService.clampSpeed(3.0)).isEqualTo(1.4);
        assertThat(SettingsService.clampSpeed(0.1)).isEqualTo(0.7);
    }
}
