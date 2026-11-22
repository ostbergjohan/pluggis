package se.ostberg.pluggis.usage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Priser i USD per miljon token (Claudes API-prislista) och växelkurs till kronor.
 * Modeller matchas på början av namnet, så "claude-haiku-5-5-20261007" får haiku-5-5-priset.
 *
 * @param usdSek       växelkurs (kronor per dollar)
 * @param azureFreeChars gratis tecken per månad i Azures F0-nivå
 */
@ConfigurationProperties("pluggis.costs")
public record CostProperties(Double usdSek, Long azureFreeChars, Map<String, Price> prices) {

    /** {@code longContextMultiplier} gäller anrop med fler än 100 000 token in (Haiku 5.5). */
    public record Price(double input, double output, double cacheRead, double cacheWrite, Double longContextMultiplier) {
    }

    static final Map<String, Price> DEFAULTS = new LinkedHashMap<>();

    static {
        DEFAULTS.put("claude-haiku-5-5", new Price(0.10, 0.50, 0.01, 0.125, 5.0));
        DEFAULTS.put("claude-sonnet-5-5", new Price(2.00, 10.00, 0.10, 2.50, null));
        DEFAULTS.put("claude-opus-5-5", new Price(4.00, 20.00, 0.20, 5.00, null));
    }

    public double rate() {
        return usdSek == null || usdSek <= 0 ? 10.0 : usdSek;
    }

    public long freeChars() {
        return azureFreeChars == null || azureFreeChars <= 0 ? 500_000 : azureFreeChars;
    }

    public Map<String, Price> allPrices() {
        Map<String, Price> all = new LinkedHashMap<>(DEFAULTS);
        if (prices != null) {
            all.putAll(prices);
        }
        return all;
    }

    /** Längsta matchande prefix vinner. Okänd modell → null (kostnaden visas som 0). */
    public Price priceFor(String model) {
        if (model == null) {
            return null;
        }
        String best = null;
        for (String key : allPrices().keySet()) {
            if (model.startsWith(key) && (best == null || key.length() > best.length())) {
                best = key;
            }
        }
        return best == null ? null : allPrices().get(best);
    }

    public double costUsd(String model, long input, long output, long cacheRead, long cacheWrite) {
        Price p = priceFor(model);
        if (p == null) {
            return 0;
        }
        double usd = (input * p.input() + output * p.output() + cacheRead * p.cacheRead() + cacheWrite * p.cacheWrite()) / 1_000_000d;
        if (p.longContextMultiplier() != null && input + cacheRead + cacheWrite > 100_000) {
            usd *= p.longContextMultiplier();
        }
        return usd;
    }
}
