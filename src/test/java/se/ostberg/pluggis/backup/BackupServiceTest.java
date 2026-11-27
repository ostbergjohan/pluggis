package se.ostberg.pluggis.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import se.ostberg.pluggis.settings.SettingsService;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class BackupServiceTest {

    @TempDir
    Path dir;

    @Test
    void kopieraListaOchRensa() throws Exception {
        String url = "jdbc:h2:file:" + dir.resolve("db").toAbsolutePath();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        jdbc.execute("CREATE TABLE t(x INT)");
        jdbc.execute("INSERT INTO t VALUES (1)");
        BackupService b = new BackupService(jdbc, mock(SettingsService.class), dir.resolve("backups").toString(), 2, url, "Europe/Stockholm");
        assertThat(b.supported()).isTrue();
        var first = b.create();
        assertThat(first.name()).matches(BackupService.NAME.pattern());
        assertThat(Files.size(b.file(first.name()))).isGreaterThan(0);
        b.create();
        b.create();
        assertThat(b.list()).hasSize(2);   // bara de två senaste sparas
        assertThat(b.file("../db.mv.db")).isNull();
        assertThat(b.file("annat.zip")).isNull();
    }

    @Test
    void postgresGorInteKopior() {
        BackupService b = new BackupService(mock(JdbcTemplate.class), mock(SettingsService.class), dir.toString(), 14,
                "jdbc:postgresql://localhost/pluggis", "Europe/Stockholm");
        assertThat(b.supported()).isFalse();
    }
}
