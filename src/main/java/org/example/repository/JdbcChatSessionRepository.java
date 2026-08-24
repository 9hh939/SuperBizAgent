package org.example.repository;

import org.example.model.ChatHistoryMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcChatSessionRepository implements ChatSessionRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcChatSessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public ChatSessionMetadata getOrCreate(String sessionId) {
        Optional<ChatSessionMetadata> existing = findSession(sessionId);
        if (existing.isPresent()) {
            return existing.get();
        }

        long now = System.currentTimeMillis();
        jdbcTemplate.update(
                "INSERT INTO chat_session (session_id, created_at, updated_at) VALUES (?, ?, ?)",
                sessionId, now, now
        );
        return new ChatSessionMetadata(sessionId, now, 0);
    }

    @Override
    public Optional<ChatSessionMetadata> findSession(String sessionId) {
        List<ChatSessionMetadata> sessions = jdbcTemplate.query(
                """
                SELECT s.session_id, s.created_at, COUNT(t.id) AS pair_count
                FROM chat_session s
                LEFT JOIN chat_turn t ON t.session_id = s.session_id
                WHERE s.session_id = ?
                GROUP BY s.session_id, s.created_at
                """,
                (resultSet, rowNum) -> new ChatSessionMetadata(
                        resultSet.getString("session_id"),
                        resultSet.getLong("created_at"),
                        resultSet.getInt("pair_count")
                ),
                sessionId
        );
        return sessions.stream().findFirst();
    }

    @Override
    public List<ChatHistoryMessage> findRecentMessages(String sessionId, int maxMessagePairs) {
        List<StoredTurn> newestFirst = jdbcTemplate.query(
                """
                SELECT user_content, assistant_content
                FROM chat_turn
                WHERE session_id = ?
                ORDER BY id DESC
                LIMIT ?
                """,
                (resultSet, rowNum) -> new StoredTurn(
                        resultSet.getString("user_content"),
                        resultSet.getString("assistant_content")
                ),
                sessionId,
                maxMessagePairs
        );
        Collections.reverse(newestFirst);

        List<ChatHistoryMessage> messages = new ArrayList<>(newestFirst.size() * 2);
        for (StoredTurn turn : newestFirst) {
            messages.add(new ChatHistoryMessage("user", turn.userContent()));
            messages.add(new ChatHistoryMessage("assistant", turn.assistantContent()));
        }
        return List.copyOf(messages);
    }

    @Override
    @Transactional
    public void appendTurn(
            String sessionId,
            String userContent,
            String assistantContent,
            int maxMessagePairs
    ) {
        getOrCreate(sessionId);
        long now = System.currentTimeMillis();
        jdbcTemplate.update(
                """
                INSERT INTO chat_turn (session_id, user_content, assistant_content, created_at)
                VALUES (?, ?, ?, ?)
                """,
                sessionId, userContent, assistantContent, now
        );
        jdbcTemplate.update(
                "UPDATE chat_session SET updated_at = ? WHERE session_id = ?",
                now, sessionId
        );
        trimOldTurns(sessionId, maxMessagePairs);
    }

    private void trimOldTurns(String sessionId, int maxMessagePairs) {
        List<Long> turnIds = jdbcTemplate.queryForList(
                "SELECT id FROM chat_turn WHERE session_id = ? ORDER BY id ASC",
                Long.class,
                sessionId
        );
        int deleteCount = turnIds.size() - maxMessagePairs;
        for (int index = 0; index < deleteCount; index++) {
            jdbcTemplate.update("DELETE FROM chat_turn WHERE id = ?", turnIds.get(index));
        }
    }

    @Override
    @Transactional
    public boolean clearHistory(String sessionId) {
        if (findSession(sessionId).isEmpty()) {
            return false;
        }
        jdbcTemplate.update("DELETE FROM chat_turn WHERE session_id = ?", sessionId);
        jdbcTemplate.update(
                "UPDATE chat_session SET updated_at = ? WHERE session_id = ?",
                System.currentTimeMillis(), sessionId
        );
        return true;
    }

    @Override
    @Transactional
    public boolean deleteSession(String sessionId) {
        return jdbcTemplate.update("DELETE FROM chat_session WHERE session_id = ?", sessionId) > 0;
    }

    private record StoredTurn(String userContent, String assistantContent) {
    }
}
