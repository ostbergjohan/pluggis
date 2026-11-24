package se.ostberg.pluggis.games;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrderCleanTest {

    @Test
    void behållerBaraSpelbaraOrdningar() {
        var g = new GamesController.Generated(List.of(
                new GamesController.Sequence("Fotosyntesen", null, List.of("Ljus fångas", "Vatten delas", "Socker bildas", "Syre släpps ut"), "För att…"),
                new GamesController.Sequence("För kort", "x", List.of("a", "b"), null),
                new GamesController.Sequence("Dubblett", "x", List.of("a", "b", "a", "c"), null),
                new GamesController.Sequence(null, null, List.of("1", "2", "3"), null)), null);
        var out = GamesController.clean(g);
        assertThat(out).hasSize(2);
        assertThat(out.get(0).prompt()).isEqualTo("Tryck i rätt ordning");
        assertThat(out.get(1).title()).isEqualTo("Rätt ordning");
    }
}
