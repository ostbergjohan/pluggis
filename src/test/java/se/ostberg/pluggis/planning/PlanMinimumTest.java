package se.ostberg.pluggis.planning;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class PlanMinimumTest {

    @Test
    void minstEnKvartOchInteBaraRubriker() throws Exception {
        assertThat(PlanningService.MIN_ITEM_MINUTES).isEqualTo(15);
        var f = PlanningService.class.getDeclaredField("SKIM_ONLY");
        f.setAccessible(true);
        Pattern skim = (Pattern) f.get(null);
        assertThat(skim.matcher("Ta fram NO-boken och läs bara rubrikerna på s. 40–48").find()).isTrue();
        assertThat(skim.matcher("Läs rubrikerna i kapitel 4").find()).isTrue();
        assertThat(skim.matcher("Skumma igenom sidorna 40–48").find()).isTrue();
        assertThat(skim.matcher("Läs s. 40–45 och skriv 3 nyckelord").find()).isFalse();
        assertThat(skim.matcher("Gör en tankekarta med rubrikerna som grenar").find()).isFalse();
    }
}
