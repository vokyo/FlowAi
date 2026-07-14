package com.vokyo.backend.pagination;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.function.Function;

public final class CursorPagination {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;

    private CursorPagination() {
    }

    public static int validateLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Limit must be between 1 and " + MAX_LIMIT
            );
        }
        return limit;
    }

    public static <E, T> CursorPage<T> page(
            List<E> results,
            int limit,
            Function<E, T> mapper,
            Function<E, String> cursorEncoder
    ) {
        boolean hasNext = results.size() > limit;
        List<E> pageItems = hasNext ? results.subList(0, limit) : results;
        String nextCursor = hasNext
                ? cursorEncoder.apply(pageItems.get(pageItems.size() - 1))
                : null;
        return new CursorPage<>(pageItems.stream().map(mapper).toList(), nextCursor);
    }
}
