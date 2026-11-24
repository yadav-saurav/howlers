package app.howlers.storage;

import app.howlers.common.config.AppProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

/** Filesystem driver ({@code howlers.storage.driver=local}), created by {@link StorageConfig}. */
public class LocalStorageService implements StorageService {
    private final Path root;

    public LocalStorageService(AppProperties props) throws IOException {
        this.root = Path.of(props.storage().localDir()).toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        Path p = resolve(key);
        try {
            Files.createDirectories(p.getParent());
            Files.write(p, bytes, StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void put(String key, InputStream in, String contentType) {
        Path p = resolve(key);
        Path tmp = p.resolveSibling(p.getFileName() + ".part");
        try {
            Files.createDirectories(p.getParent());
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, p, StandardCopyOption.ATOMIC_MOVE); // readers never see a half-written file
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best effort
            }
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<StoredObject> get(String key) {
        if (!StorageService.isSafeKey(key)) return Optional.empty();
        Path p = resolve(key);
        if (!Files.isRegularFile(p)) return Optional.empty();
        try {
            return Optional.of(new StoredObject(Files.newInputStream(p), Files.size(p), StorageService.contentTypeFor(key)));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<Resource> resource(String key) {
        if (!StorageService.isSafeKey(key)) return Optional.empty();
        Path p = resolve(key);
        return Files.isRegularFile(p) ? Optional.of(new FileSystemResource(p)) : Optional.empty();
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException | IllegalArgumentException ignored) {
            // best effort
        }
    }

    @Override
    public boolean healthy() {
        return Files.isDirectory(root) && Files.isWritable(root);
    }

    private Path resolve(String key) {
        if (!StorageService.isSafeKey(key)) throw new IllegalArgumentException("Invalid storage key");
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) throw new IllegalArgumentException("Invalid storage key");
        return p;
    }
}
