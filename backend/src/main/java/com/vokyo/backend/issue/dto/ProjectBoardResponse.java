package com.vokyo.backend.issue.dto;

import java.util.List;
import java.util.UUID;

public record ProjectBoardResponse(
        UUID projectId,
        List<BoardColumnResponse> columns
) {
}
