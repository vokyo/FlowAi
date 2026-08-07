package com.vokyo.backend.demo;

import com.vokyo.backend.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static com.vokyo.backend.auth.EmailAddressNormalizer.normalize;

/**
 * Populates a public deployment with a worked-in workspace on startup, so that a
 * visitor sees the board, the filters, the analytics trend and a saved Copilot
 * draft instead of an empty app.
 *
 * <p>Two properties of this runner matter more than what it writes:
 *
 * <ul>
 *   <li><b>It is idempotent.</b> The demo account is the marker: if that email is
 *       already registered, the runner returns without writing. Restarts and
 *       manual re-runs are no-ops; a database reset seeds again from scratch.
 *   <li><b>It is atomic.</b> The whole dataset is written in one transaction, so a
 *       failure half way through rolls back rather than leaving the demo account
 *       behind — which would make every later run skip a workspace that was never
 *       finished.
 * </ul>
 *
 * <p>Nothing here talks to a model provider. The Copilot draft is a stored
 * fixture, so the demo can keep {@code AI_ENABLED} off and still show the Apply
 * flow at no cost.
 */
@Component
@ConditionalOnProperty(prefix = "app.demo", name = "enabled", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final DemoSeedProperties properties;
    private final UserRepository userRepository;
    private final DemoWorkspaceSeeder workspaceSeeder;
    private final TransactionTemplate transactionTemplate;

    public DemoDataSeeder(
            DemoSeedProperties properties,
            UserRepository userRepository,
            DemoWorkspaceSeeder workspaceSeeder,
            PlatformTransactionManager transactionManager
    ) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.workspaceSeeder = workspaceSeeder;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            seed();
        } catch (RuntimeException exception) {
            /*
             * An unseeded demo is a much smaller problem than a backend that will
             * not start, so the deployment keeps serving and the failure is loud
             * in the logs instead.
             */
            log.error("Demo data seeding failed; the workspace was not created", exception);
        }
    }

    /**
     * Seeds the demo workspace unless the demo account already exists.
     *
     * @return what the call did, so callers and tests can tell a first run from a
     *         repeat without inspecting the database
     */
    public DemoSeedOutcome seed() {
        String email = normalize(properties.email());
        if (userRepository.existsByEmail(email)) {
            log.info("Demo data already present for {}; skipping the seeder", email);
            return DemoSeedOutcome.ALREADY_PRESENT;
        }

        try {
            DemoSeedSummary summary = transactionTemplate.execute(status ->
                    workspaceSeeder.seed(properties)
            );
            log.info(
                    "Seeded demo workspace '{}' for {}: {} projects, {} issues, {} comments,"
                            + " 1 saved AI suggestion",
                    properties.workspaceName(),
                    email,
                    summary.projects(),
                    summary.issues(),
                    summary.comments()
            );
            return DemoSeedOutcome.SEEDED;
        } catch (DataIntegrityViolationException exception) {
            /*
             * Two instances booting together both pass the existence check above.
             * users.email is unique, so the loser's transaction rolls back whole
             * and the winner's dataset stands.
             */
            log.info("Demo data was seeded concurrently by another instance; skipping");
            return DemoSeedOutcome.ALREADY_PRESENT;
        }
    }

    public enum DemoSeedOutcome {
        SEEDED,
        ALREADY_PRESENT
    }
}
