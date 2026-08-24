package org.example.service;

import org.example.config.ChatHistoryProperties;
import org.example.repository.JdbcChatSessionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@JdbcTest
@Import(JdbcChatSessionRepository.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ChatSessionServiceTest {

    @Autowired
    private JdbcChatSessionRepository repository;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void shutDownExecutor() {
        executor.shutdownNow();
    }

    @Test
    void secondTurnForSameSessionWaitsUntilFirstTurnCompletes() throws Exception {
        ChatHistoryProperties properties = new ChatHistoryProperties();
        properties.setMaxMessagePairs(6);
        ChatSessionService service = new ChatSessionService(repository, properties);

        ChatSessionService.SessionTurn first = service.beginTurn("session-concurrent", "问题1");
        Future<ChatSessionService.SessionTurn> secondFuture =
                executor.submit(() -> service.beginTurn("session-concurrent", "问题2"));

        assertThatThrownBy(() -> secondFuture.get(150, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

        first.complete("回答1");
        ChatSessionService.SessionTurn second = secondFuture.get(2, TimeUnit.SECONDS);
        assertThat(second.history()).hasSize(2);
        second.complete("回答2");

        assertThat(repository.findSession("session-concurrent"))
                .get()
                .extracting(metadata -> metadata.messagePairCount())
                .isEqualTo(2);
    }

    @Test
    void abortedTurnReleasesSessionForNextRequestWithoutSavingMessages() throws Exception {
        ChatHistoryProperties properties = new ChatHistoryProperties();
        ChatSessionService service = new ChatSessionService(repository, properties);

        ChatSessionService.SessionTurn first = service.beginTurn("session-abort", "失败问题");
        first.abort();

        ChatSessionService.SessionTurn second = service.beginTurn("session-abort", "成功问题");
        assertThat(second.history()).isEmpty();
        second.complete("成功回答");

        assertThat(repository.findSession("session-abort"))
                .get()
                .extracting(metadata -> metadata.messagePairCount())
                .isEqualTo(1);
    }

    @Test
    void abortedTurnCannotLaterBeCompletedAsSuccessful() {
        ChatHistoryProperties properties = new ChatHistoryProperties();
        ChatSessionService service = new ChatSessionService(repository, properties);

        ChatSessionService.SessionTurn turn = service.beginTurn("session-timeout", "超时问题");
        turn.abort();

        assertThatThrownBy(() -> turn.complete("迟到的回答"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("会话轮次已经结束");
        assertThat(repository.findSession("session-timeout"))
                .get()
                .extracting(metadata -> metadata.messagePairCount())
                .isEqualTo(0);
    }

    @Test
    void cancelledRequestDoesNotStartAfterWaitingForSameSession() throws Exception {
        ChatHistoryProperties properties = new ChatHistoryProperties();
        ChatSessionService service = new ChatSessionService(repository, properties);
        AtomicBoolean cancelled = new AtomicBoolean(false);

        ChatSessionService.SessionTurn first = service.beginTurn("session-cancel", "问题1");
        Future<ChatSessionService.SessionTurn> waiting = executor.submit(
                () -> service.beginTurn("session-cancel", "问题2", cancelled::get)
        );

        assertThatThrownBy(() -> waiting.get(150, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
        cancelled.set(true);
        first.complete("回答1");

        assertThatThrownBy(() -> waiting.get(2, TimeUnit.SECONDS))
                .hasCauseInstanceOf(java.util.concurrent.CancellationException.class);
        assertThat(repository.findSession("session-cancel"))
                .get()
                .extracting(metadata -> metadata.messagePairCount())
                .isEqualTo(1);
    }
}
