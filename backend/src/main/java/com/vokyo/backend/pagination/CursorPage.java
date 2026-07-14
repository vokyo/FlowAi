package com.vokyo.backend.pagination;

import java.util.List;

public record CursorPage<T>(
        List<T> items,
        String nextCursor
) {
}
