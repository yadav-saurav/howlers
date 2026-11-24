package app.howlers.podcast;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.stereotype.Component;

/**
 * The small background executor podcast jobs share: AI script drafts ({@link ScriptDrafter}) and audio generation
 * ({@code AudioGenerator}). Two threads, so a long generation can't starve drafting entirely; extra jobs queue.
 */
@Component
public class PodcastJobs {
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "podcast-jobs");
        t.setDaemon(true);
        return t;
    });

    public Future<?> submit(Runnable task) {
        return executor.submit(task);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
