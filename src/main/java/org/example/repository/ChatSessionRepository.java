package org.example.repository;

import org.example.model.ChatHistoryMessage;

import java.util.List;
import java.util.Optional;

public interface ChatSessionRepository {

    ChatSessionMetadata getOrCreate(String sessionId);

    Optional<ChatSessionMetadata> findSession(String sessionId);

    List<ChatHistoryMessage> findRecentMessages(String sessionId, int maxMessagePairs);

    void appendTurn(String sessionId, String userContent, String assistantContent, int maxMessagePairs);

    boolean clearHistory(String sessionId);

    boolean deleteSession(String sessionId);
}
