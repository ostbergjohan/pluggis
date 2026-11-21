package se.ostberg.pluggis.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;

/**
 * Små schemaändringar som {@code ddl-auto: update} inte gör själv. Körs vid start och tål att köras igen.
 * <p>
 * Typen på läxor fick värdet OTHER ("annat"). Äldre databaser har kolumnen som enum (H2)
 * eller med en check-constraint (Postgres) som bara tillåter HOMEWORK/TEST.
 */
@Component
@Order(0)
public class SchemaFixes implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaFixes.class);

    private final JdbcTemplate jdbc;

    public SchemaFixes(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        String product = jdbc.execute((Connection c) -> c.getMetaData().getDatabaseProductName());
        if (product == null) {
            return;
        }
        if (product.toLowerCase().contains("h2")) {
            String type = jdbc.query("select data_type from information_schema.columns "
                            + "where upper(table_name) = 'HOMEWORK' and upper(column_name) = 'KIND'",
                    rs -> rs.next() ? rs.getString(1) : null);
            if (type != null && type.toUpperCase().contains("ENUM")) {
                jdbc.execute("alter table homework alter column kind set data type varchar(20)");
                log.info("Schema: homework.kind ändrad från enum till varchar");
            }
        } else if (product.toLowerCase().contains("postgres")) {
            jdbc.execute("alter table homework drop constraint if exists homework_kind_check");
        }
    }
}
