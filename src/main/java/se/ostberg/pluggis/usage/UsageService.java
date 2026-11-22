package se.ostberg.pluggis.usage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import se.ostberg.pluggis.config.PluggisProperties;
import se.ostberg.pluggis.student.StudentContext;

import java.time.LocalDate;

/** Sparar förbrukning per dag. Får aldrig störa själva anropet – fel loggas och ignoreras. */
@Service
public class UsageService {

    private static final Logger log = LoggerFactory.getLogger(UsageService.class);

    private final UsageDayRepository repo;
    private final CostProperties costs;
    private final PluggisProperties props;
    private final TransactionTemplate tx;

    public UsageService(UsageDayRepository repo, CostProperties costs, PluggisProperties props, PlatformTransactionManager tm) {
        this.repo = repo;
        this.costs = costs;
        this.props = props;
        this.tx = new TransactionTemplate(tm);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void recordClaude(String purpose, String model, boolean subscription,
                             long input, long output, long cacheRead, long cacheWrite) {
        double usd = costs.costUsd(model, input, output, cacheRead, cacheWrite);
        save(purpose, model == null ? "?" : model, subscription ? "subscription" : "api", input, output, cacheRead, cacheWrite, 0, usd);
    }

    public void recordTts(String voice, long characters) {
        save("tts", voice == null ? "azure" : voice, "azure", 0, 0, 0, 0, characters, 0);
    }

    private synchronized void save(String purpose, String model, String backend,
                                   long input, long output, long cacheRead, long cacheWrite, long chars, double usd) {
        try {
            LocalDate day = LocalDate.now(props.zone());
            Long student = StudentContext.currentOrDefault();
            tx.executeWithoutResult(s -> {
                UsageDay row = repo.findFirstByDayAndStudentIdAndPurposeAndModelAndBackend(day, student, purpose, model, backend)
                        .orElseGet(() -> new UsageDay(day, student, purpose, model, backend));
                row.add(input, output, cacheRead, cacheWrite, chars, usd);
                repo.save(row);
            });
        } catch (RuntimeException e) {
            log.warn("Kunde inte spara förbrukning för {}: {}", purpose, e.toString());
        }
    }
}
