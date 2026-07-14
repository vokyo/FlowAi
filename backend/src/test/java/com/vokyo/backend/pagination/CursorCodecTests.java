package com.vokyo.backend.pagination;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CursorCodecTests {

    private final CursorCodec codec = new CursorCodec();

    @Test
    void roundTripsTimeAndBoardCursors() {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-07-14T08:00:00.123456Z");

        String timeCursor = codec.encodeTime("issues:workspace:project", createdAt, id);
        String boardCursor = codec.encodeBoard("board:state", 42_000L, id);

        assertThat(codec.decodeTime(timeCursor, "issues:workspace:project"))
                .isEqualTo(new CursorCodec.TimeCursor(createdAt, id));
        assertThat(codec.decodeBoard(boardCursor, "board:state"))
                .isEqualTo(new CursorCodec.BoardCursor(42_000L, id));
        assertThat(timeCursor).doesNotContain("=");
    }

    @Test
    void rejectsMalformedNonCanonicalAndWrongScopeCursors() {
        String valid = codec.encodeTime("expected", Instant.EPOCH, UUID.randomUUID());
        String unknownField = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("1\nexpected\n1970-01-01T00:00:00Z\nid\nextra".getBytes());

        assertInvalid("not+padded=");
        assertInvalid(unknownField);
        assertThatThrownBy(() -> codec.decodeTime(valid, "different"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST)
                );
    }

    private void assertInvalid(String cursor) {
        assertThatThrownBy(() -> codec.decodeTime(cursor, "expected"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST)
                );
    }
}
