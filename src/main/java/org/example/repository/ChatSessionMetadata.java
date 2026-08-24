package org.example.repository;

public record ChatSessionMetadata(String sessionId, long createTime, int messagePairCount) {
}
