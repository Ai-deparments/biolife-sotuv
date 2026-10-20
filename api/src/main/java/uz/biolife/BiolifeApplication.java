package uz.biolife;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * BioLife Agent AI · modular monolith.
 * Modules: shared (open), identity, ingest (temporary fact intake), rating, reporting, visit (panel visit list), sources (data freshness).
 * Module boundaries are verified by {@code ModularityTests}.
 */
@SpringBootApplication
@Modulithic(systemName = "BioLife Agent AI", sharedModules = "shared")
@EnableAsync
@EnableScheduling
public class BiolifeApplication {
    public static void main(String[] args) {
        SpringApplication.run(BiolifeApplication.class, args);
    }
}
