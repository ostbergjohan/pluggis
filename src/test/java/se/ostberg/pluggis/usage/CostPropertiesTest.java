package se.ostberg.pluggis.usage;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CostPropertiesTest {

    private final CostProperties costs = new CostProperties(10.0, null, null);

    @Test
    void raknarHaikuOchSonnet() {
        // 1 M in + 1 M ut på Haiku 5.5 = 0,10 + 0,50 USD
        assertThat(costs.costUsd("claude-haiku-5-5", 50_000, 1_000_000, 0, 0) + costs.costUsd("claude-haiku-5-5", 50_000, 0, 0, 0) * 19)
                .isCloseTo(0.60, within(1e-9));
        assertThat(costs.costUsd("claude-sonnet-5-5-20260928", 1_000_000 / 20, 0, 0, 0)).isCloseTo(0.10, within(1e-9));
    }

    @Test
    void langaAnropPaHaikuKostarFemGanger() {
        double kort = costs.costUsd("claude-haiku-5-5", 100_000, 1000, 0, 0);
        double lang = costs.costUsd("claude-haiku-5-5", 100_001, 1000, 0, 0);
        assertThat(lang / kort).isGreaterThan(4.9);
    }

    @Test
    void okandModellKostarNollOchEgnaPriserGarAttLaggaTill() {
        assertThat(costs.costUsd("nagot-annat", 1_000_000, 0, 0, 0)).isZero();
        var egna = new CostProperties(null, null, Map.of("min-modell", new CostProperties.Price(1, 2, 0, 0, null)));
        assertThat(egna.costUsd("min-modell-v2", 1_000_000, 1_000_000, 0, 0)).isCloseTo(3.0, within(1e-9));
        assertThat(egna.rate()).isEqualTo(10.0);
        assertThat(egna.freeChars()).isEqualTo(500_000);
    }
}
