package uz.biolife.shared.time;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** One injectable clock so schedulers and "today" logic are testable. */
@Configuration
public class ClockConfig {
    @Bean
    public Clock clock() {
        return Clock.system(WorkHours.ZONE);
    }
}
