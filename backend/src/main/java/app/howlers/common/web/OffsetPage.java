package app.howlers.common.web;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

public record OffsetPage<T>(List<T> items, int page, int size, long totalElements, int totalPages) {
    public static <E, T> OffsetPage<T> of(Page<E> p, Function<E, T> mapper) {
        return new OffsetPage<>(p.getContent().stream().map(mapper).toList(), p.getNumber(), p.getSize(),
                p.getTotalElements(), p.getTotalPages());
    }

    public static <T> OffsetPage<T> of(List<T> items, int page, int size, long total) {
        return new OffsetPage<>(items, page, size, total, size == 0 ? 0 : (int) Math.ceil(total / (double) size));
    }
}
