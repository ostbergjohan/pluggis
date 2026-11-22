package se.ostberg.pluggis.student;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Vid start: skapa första eleven om ingen finns, och koppla befintliga läxor, plan, material
 * och flashkort (från före flera-elever-stödet) till den.
 */
@Component
@Order(1)
public class StudentMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StudentMigration.class);
    public static final String[] TABLES = {"homework", "material", "plan_item", "deck", "card"};

    private final StudentService students;
    private final JdbcTemplate jdbc;

    public StudentMigration(StudentService students, JdbcTemplate jdbc) {
        this.students = students;
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        Student first = students.ensureDefault();
        for (String table : TABLES) {
            try {
                int n = jdbc.update("update " + table + " set student_key = ? where student_key is null", first.key());
                if (n > 0) {
                    log.info("Kopplade {} rader i {} till eleven {}", n, table, first.getId());
                }
            } catch (RuntimeException e) {
                log.debug("Kunde inte uppdatera {}: {}", table, e.getMessage());
            }
        }
    }
}
