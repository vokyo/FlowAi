package com.vokyo.backend.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlanContentHashTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void theSamePlanHashesTheSameWhateverOrderItsFieldsArriveIn() throws Exception {
        String hash = PlanContentHash.of(objectMapper.readTree("""
            {"overview": "Clear the login debt",
             "items": [{"title": "Add refresh token tests", "priority": "HIGH", "clientItemId": "item-1"}]}
            """));
        String reordered = PlanContentHash.of(objectMapper.readTree("""
            {"items": [{"clientItemId": "item-1", "priority": "HIGH", "title": "Add refresh token tests"}],
             "overview": "Clear the login debt"}
            """));

        assertThat(reordered).isEqualTo(hash).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void anyChangeToThePlanChangesTheHash() throws Exception {
        String hash = PlanContentHash.of(objectMapper.readTree("""
            {"overview": "Clear the login debt", "items": [{"clientItemId": "item-1", "priority": "HIGH"}]}
            """));
        String changed = PlanContentHash.of(objectMapper.readTree("""
            {"overview": "Clear the login debt", "items": [{"clientItemId": "item-1", "priority": "LOW"}]}
            """));
        String reorderedItems = PlanContentHash.of(objectMapper.readTree("""
            {"overview": "Clear the login debt",
             "items": [{"clientItemId": "item-2", "priority": "HIGH"}, {"clientItemId": "item-1", "priority": "HIGH"}]}
            """));

        assertThat(changed).isNotEqualTo(hash);
        assertThat(reorderedItems).isNotEqualTo(hash);
    }
}
