package app.howlers.reaction;

import java.util.List;
import java.util.Optional;

/**
 * The curated reaction set (D-044). Reactions are stored by key, never by raw emoji, so the set can be restyled
 * without a data migration and nobody can store arbitrary text through this path. The order here is the picker order.
 */
public final class ReactionCatalog {
    private ReactionCatalog() {}

    public record Kind(String key, String emoji, String label) {}

    public static final List<Kind> ALL = List.of(
            new Kind("like", "👍", "Thumbs up"),
            new Kind("heart", "❤️", "Heart"),
            new Kind("laugh", "😂", "Laughing"),
            new Kind("wow", "😮", "Surprised"),
            new Kind("sad", "😢", "Sad"),
            new Kind("fire", "🔥", "Fire"),
            new Kind("howl", "🐺", "Howl"),
            new Kind("party", "🎉", "Party"),
            new Kind("clap", "👏", "Applause"),
            new Kind("think", "🤔", "Thinking"));

    public static Optional<Kind> find(String key) {
        if (key == null) return Optional.empty();
        String k = key.strip().toLowerCase(java.util.Locale.ROOT);
        return ALL.stream().filter(x -> x.key().equals(k)).findFirst();
    }

    static int order(String key) {
        for (int i = 0; i < ALL.size(); i++) if (ALL.get(i).key().equals(key)) return i;
        return ALL.size();
    }
}
