package org.example.service;

import org.example.config.ChatHistoryProperties;
import org.example.model.ChatHistoryMessage;
import org.example.repository.ChatSessionMetadata;
import org.example.repository.ChatSessionRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

@Service
public class ChatSessionService {

    private static final int LOCK_STRIPE_COUNT = 64;

    private final ChatSessionRepository repository;
    private final ChatHistoryProperties properties;
    private final Semaphore[] sessionLocks;

    public ChatSessionService(ChatSessionRepository repository, ChatHistoryProperties properties) {
        this.repository = repository;
        this.properties = properties;
        this.sessionLocks = new Semaphore[LOCK_STRIPE_COUNT];
        for (int index = 0; index < sessionLocks.length; index++) {
            sessionLocks[index] = new Semaphore(1, true);
        }
    }

    public SessionTurn beginTurn(String requestedSessionId, String userQuestion) {
        return beginTurn(requestedSessionId, userQuestion, () -> false);
    }

    public SessionTurn beginTurn(
            String requestedSessionId,
            String userQuestion,
            BooleanSupplier cancelled
    ) {
        if (cancelled.getAsBoolean()) {
            throw new java.util.concurrent.CancellationException("会话请求已取消");
        }
        String sessionId = normalizeSessionId(requestedSessionId);
        Semaphore sessionLock = lockFor(sessionId);
        acquire(sessionLock);
        try {
            if (cancelled.getAsBoolean()) {
                throw new java.util.concurrent.CancellationException("会话请求已取消");
            }
            repository.getOrCreate(sessionId);
            List<ChatHistoryMessage> history = repository.findRecentMessages(
                    sessionId,
                    properties.getMaxMessagePairs()
            );
            return new SessionTurn(
                    sessionId,
                    userQuestion,
                    history,
                    repository,
                    properties.getMaxMessagePairs(),
                    sessionLock
            );
        } catch (RuntimeException exception) {
            sessionLock.release();
            throw exception;
        }
    }

    public Optional<ChatSessionMetadata> findSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return withLock(sessionId, () -> repository.findSession(sessionId));
    }

    public boolean clearHistory(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        return withLock(sessionId, () -> repository.clearHistory(sessionId));
    }

    public boolean deleteSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        return withLock(sessionId, () -> repository.deleteSession(sessionId));
    }

    private String normalizeSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        if (sessionId.length() > 255) {
            throw new IllegalArgumentException("会话ID不能超过255个字符");
        }
        return sessionId;
    }

    private Semaphore lockFor(String sessionId) {
        int index = Math.floorMod(sessionId.hashCode(), sessionLocks.length);
        return sessionLocks[index];
    }

    private void acquire(Semaphore semaphore) {
        try {
            semaphore.acquire();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待会话锁时被中断", exception);
        }
    }

    private <T> T withLock(String sessionId, LockedOperation<T> operation) {
        Semaphore sessionLock = lockFor(sessionId);
        acquire(sessionLock);
        try {
            return operation.execute();
        } finally {
            sessionLock.release();
        }
    }

    @FunctionalInterface
    private interface LockedOperation<T> {
        T execute();
    }

    public static final class SessionTurn {
        private final String sessionId;
        private final String userQuestion;
        private final List<ChatHistoryMessage> history;
        private final ChatSessionRepository repository;
        private final int maxMessagePairs;
        private final Semaphore sessionLock;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private SessionTurn(
                String sessionId,
                String userQuestion,
                List<ChatHistoryMessage> history,
                ChatSessionRepository repository,
                int maxMessagePairs,
                Semaphore sessionLock
        ) {
            this.sessionId = sessionId;
            this.userQuestion = userQuestion;
            this.history = List.copyOf(history);
            this.repository = repository;
            this.maxMessagePairs = maxMessagePairs;
            this.sessionLock = sessionLock;
        }

        public String sessionId() {
            return sessionId;
        }

        public List<ChatHistoryMessage> history() {
            return history;
        }

        public void complete(String assistantAnswer) {
            if (!closed.compareAndSet(false, true)) {
                throw new IllegalStateException("会话轮次已经结束");
            }
            try {
                repository.appendTurn(
                        sessionId,
                        userQuestion,
                        assistantAnswer,
                        maxMessagePairs
                );
            } finally {
                sessionLock.release();
            }
        }

        public void abort() {
            if (closed.compareAndSet(false, true)) {
                sessionLock.release();
            }
        }
    }
}
