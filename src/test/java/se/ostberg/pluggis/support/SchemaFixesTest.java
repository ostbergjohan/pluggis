package se.ostberg.pluggis.support;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaFixesTest {

    @Test
    void addsStudentKeyToExistingTables() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:schemafix2;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table homework (id bigint primary key, title varchar(100))");
        jdbc.execute("create table plan_item (id bigint primary key)");
        jdbc.update("insert into homework values (1, 'Matte')");

        new SchemaFixes(jdbc).run(new DefaultApplicationArguments());
        new SchemaFixes(jdbc).run(new DefaultApplicationArguments()); // tål att köras igen

        jdbc.update("update homework set student_key = '1'");
        assertThat(jdbc.queryForObject("select student_key from homework where id = 1", String.class)).isEqualTo("1");
        jdbc.queryForObject("select count(student_key) from plan_item", Integer.class);
    }

    @Test
    void oldH2EnumColumnAcceptsOtherAfterFix() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:schemafix;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table homework (id bigint primary key, kind enum('HOMEWORK','TEST'))");
        jdbc.update("insert into homework values (1, 'TEST')");
        assertThatThrownBy(() -> jdbc.update("insert into homework values (2, 'OTHER')")).isNotNull();

        new SchemaFixes(jdbc).run(new DefaultApplicationArguments());
        new SchemaFixes(jdbc).run(new DefaultApplicationArguments()); // tål att köras igen

        jdbc.update("insert into homework values (2, 'OTHER')");
        assertThat(jdbc.queryForObject("select kind from homework where id = 1", String.class)).isEqualTo("TEST");
        assertThat(jdbc.queryForObject("select kind from homework where id = 2", String.class)).isEqualTo("OTHER");
    }
}
