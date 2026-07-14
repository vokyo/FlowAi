package com.vokyo.backend.pagination;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class CursorCodec {

    private static final String VERSION = "1";
    private static final int MAX_CURSOR_LENGTH = 1024;
    private static final Pattern BASE64_URL = Pattern.compile("[A-Za-z0-9_-]+");

    public String encodeTime(String scope, Instant createdAt, UUID id) {
        return encode(scope, createdAt.toString(), id);
    }

    public TimeCursor decodeTime(String cursor, String expectedScope) {
        String[] parts = decode(cursor, expectedScope);
        try {
            return new TimeCursor(Instant.parse(parts[2]), UUID.fromString(parts[3]));
        } catch (DateTimeParseException | IllegalArgumentException exception) {
            throw invalidCursor();
        }
    }

    public String encodeBoard(String scope, long boardPosition, UUID id) {
        return encode(scope, Long.toString(boardPosition), id);
    }

    public BoardCursor decodeBoard(String cursor, String expectedScope) {
        String[] parts = decode(cursor, expectedScope);
        try {
            return new BoardCursor(Long.parseLong(parts[2]), UUID.fromString(parts[3]));
        } catch (IllegalArgumentException exception) {
            throw invalidCursor();
        }
    }

    private String encode(String scope, String sortValue, UUID id) {
        if (scope == null || scope.isBlank() || scope.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Cursor scope is invalid");
        }
        String value = String.join("\n", VERSION, scope, sortValue, id.toString());
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String[] decode(String cursor, String expectedScope) {
        if (cursor == null || cursor.isBlank() || cursor.length() > MAX_CURSOR_LENGTH
                || !BASE64_URL.matcher(cursor).matches()) {
            throw invalidCursor();
        }

        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(cursor);
        } catch (IllegalArgumentException exception) {
            throw invalidCursor();
        }

        String canonical = Base64.getUrlEncoder().withoutPadding().encodeToString(decoded);
        if (!canonical.equals(cursor)) {
            throw invalidCursor();
        }

        String value;
        try {
            value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(decoded))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw invalidCursor();
        }

        String[] parts = value.split("\n", -1);
        if (parts.length != 4 || !VERSION.equals(parts[0]) || !expectedScope.equals(parts[1])
                || parts[2].isBlank() || parts[3].isBlank()) {
            throw invalidCursor();
        }
        return parts;
    }

    private ResponseStatusException invalidCursor() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cursor");
    }

    public record TimeCursor(Instant createdAt, UUID id) {
    }

    public record BoardCursor(long boardPosition, UUID id) {
    }
}
