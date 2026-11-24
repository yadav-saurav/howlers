package app.howlers.storage;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("storage")
public class StorageHealthIndicator implements HealthIndicator {
    private final StorageService storage;

    public StorageHealthIndicator(StorageService storage) {
        this.storage = storage;
    }

    @Override
    public Health health() {
        return storage.healthy() ? Health.up().build() : Health.down().withDetail("reason", "storage not writable").build();
    }
}
