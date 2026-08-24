package org.example.model;

/**
 * A user or assistant message that is safe to include in the model context.
 */
public record ChatHistoryMessage(String role, String content) {
}
