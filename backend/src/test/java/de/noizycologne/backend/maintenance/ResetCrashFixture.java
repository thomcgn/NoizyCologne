package de.noizycologne.backend.maintenance;

import de.noizycologne.backend.BackendApplication;
import de.noizycologne.backend.discovery.DiscoveryWorkLock;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.Path;
import java.util.Objects;

/** Standalone disposable-test JVM: an abrupt halt must not depend on finally/shutdown hooks. */
public final class ResetCrashFixture {
    private ResetCrashFixture() { }
    public static void main(String[] args) throws Exception {
        String url=Objects.requireNonNull(System.getenv("DB_URL"));
        String user=Objects.requireNonNull(System.getenv("DB_USER"));
        String password=Objects.requireNonNull(System.getenv("DB_PASSWORD"));
        if (args.length!=2 || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]{2,5}/noizy_factory_reset_[a-f0-9]{32}")
                || !user.equals("noizy_quality") || password.length()<16
                || !Path.of(args[1]).equals(Path.of("target/test-factory-reset-media").toAbsolutePath().normalize())
                || !java.util.Set.of("commit-and-halt","recover").contains(args[0]))
            throw new IllegalStateException("Crash fixture requires its isolated database and exact test media directory");
        try(var context=new SpringApplicationBuilder(BackendApplication.class).web(WebApplicationType.NONE).profiles("local").run(
                "--noizy.media.root="+args[1],"--noizy.discovery.enabled=false","--noizy.discovery.monitor.enabled=false",
                "--spring.datasource.hikari.maximum-pool-size=4","--spring.datasource.hikari.minimum-idle=0")) {
            var reset=context.getBean(FactoryResetService.class);
            if (args[0].equals("commit-and-halt")) {
                String revision=reset.preview().revision();
                try(var lease=context.getBean(MaintenanceGate.class).exclusive();
                    var discovery=context.getBean(DiscoveryWorkLock.class).tryAcquire()) {
                    if (discovery==null) throw new IllegalStateException("Fixture discovery lease unavailable");
                    reset.prepareAndCommit(lease.connection(),revision);
                    System.out.println("RESET_FIXTURE_COMMIT_CONFIRMED_BEFORE_CLEANUP");
                    Runtime.getRuntime().halt(17); // Intentional simulated crash: no finally or shutdown hook runs.
                }
            } else {
                if (!Boolean.FALSE.equals(context.getBean(JdbcTemplate.class).queryForObject(
                        "SELECT pending_reset IS NOT NULL FROM maintenance_state WHERE singleton",Boolean.class))
                        || context.getBean(ResetJournal.class).exists())
                    throw new IllegalStateException("Committed reset recovery did not finish");
                System.out.println("RESET_FIXTURE_RESTART_RECOVERY_CONFIRMED");
            }
        }
    }
}
