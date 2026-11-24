package app.howlers.common.web;

import java.util.List;
import java.util.function.Function;

public record CursorPage<T>(List<T> items, String nextCursor) {
    public <R> CursorPage<R> map(Function<T, R> fn) {
        return new CursorPage<>(items.stream().map(fn).toList(), nextCursor);
    }

    public static <T> CursorPage<T> empty() {
        return new CursorPage<>(List.of(), null);
    }
}
