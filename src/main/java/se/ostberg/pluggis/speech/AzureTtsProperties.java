package se.ostberg.pluggis.speech;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Azure AI Speech (neurala svenska röster). Valfritt – utan nyckel används Piper.
 * Skapa en Speech-resurs med prisnivån Free (F0) så kan det aldrig kosta något.
 *
 * @param key    nyckel från Azure-portalen (Keys and Endpoint)
 * @param region resursens region, t.ex. swedencentral
 */
@ConfigurationProperties("pluggis.azure-tts")
public record AzureTtsProperties(String key, String region) {

    public boolean enabled() {
        return key != null && !key.isBlank();
    }

    public String regionOrDefault() {
        return region == null || region.isBlank() ? "swedencentral" : region.trim();
    }
}
