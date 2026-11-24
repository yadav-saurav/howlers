package app.howlers.storage;

import app.howlers.common.config.AppProperties;
import java.io.IOException;
import java.util.Locale;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Picks the storage driver from {@code howlers.storage.driver}: {@code local} (default) or {@code s3} (D-023). */
@Configuration
public class StorageConfig {

    @Bean
    StorageService storageService(AppProperties props) throws IOException {
        String driver = props.storage().driver() == null ? "local" : props.storage().driver().trim().toLowerCase(Locale.ROOT);
        return switch (driver) {
            case "local", "" -> new LocalStorageService(props);
            case "s3", "minio" -> S3StorageService.create(props.storage().s3());
            default -> throw new IllegalStateException("Unknown howlers.storage.driver '" + driver + "' (use local or s3)");
        };
    }
}
