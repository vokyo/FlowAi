package com.vokyo.backend.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * The content hash of a plan version: SHA-256 of its JSON with object keys sorted,
 * so the same plan always hashes the same however its fields happen to be ordered.
 * A version is approved together with the hash the user was shown, which ties the
 * approval to the plan they read.
 */
final class PlanContentHash {

    private static final ObjectMapper JSON = new ObjectMapper();

    private PlanContentHash() {
    }

    static String of(JsonNode content) {
        try {
            byte[] canonical = JSON.writeValueAsBytes(sortedKeys(content));
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical);
            return HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Plan content cannot be hashed", exception);
        }
    }

    private static JsonNode sortedKeys(JsonNode node) {
        if (node.isObject()) {
            Map<String, JsonNode> fields = new TreeMap<>();
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                fields.put(field.getKey(), sortedKeys(field.getValue()));
            }
            ObjectNode sorted = JSON.createObjectNode();
            fields.forEach(sorted::set);
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode sorted = JSON.createArrayNode();
            node.forEach(element -> sorted.add(sortedKeys(element)));
            return sorted;
        }
        return node;
    }
}
