package org.example.repository;

import org.example.model.ChatHistoryMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@Import(JdbcChatSessionRepository.class)
class JdbcChatSessionRepositoryTest {

    @Autowired
    private JdbcChatSessionRepository repository;

    @Test
    void messagesSurviveClosingAndReopeningAFileDatabase(@TempDir Path tempDirectory) {
        String databasePath = tempDirectory.resolve("chat-history")
                .toAbsolutePath()
                .toString()
                .replace('\\', '/');
        String databaseUrl = "jdbc:h2:file:" + databasePath + ";DB_CLOSE_ON_EXIT=FALSE";

        DataSource firstDataSource = createFileDataSource(databaseUrl);
        initializeSchema(firstDataSource);
        JdbcChatSessionRepository firstRepository =
                new JdbcChatSessionRepository(new JdbcTemplate(firstDataSource));
        firstRepository.getOrCreate("session-restart");
        firstRepository.appendTurn("session-restart", "第一问", "第一答", 6);

        DataSource restartedDataSource = createFileDataSource(databaseUrl);
        initializeSchema(restartedDataSource);
        JdbcChatSessionRepository restartedRepository =
                new JdbcChatSessionRepository(new JdbcTemplate(restartedDataSource));

        assertThat(restartedRepository.findRecentMessages("session-restart", 6))
                .containsExactly(
                        new ChatHistoryMessage("user", "第一问"),
                        new ChatHistoryMessage("assistant", "第一答")
                );
    }

    private DataSource createFileDataSource(String databaseUrl) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl(databaseUrl);
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private void initializeSchema(DataSource dataSource) {
        ResourceDatabasePopulator populator =
                new ResourceDatabasePopulator(new ClassPathResource("schema.sql"));
        populator.execute(dataSource);
    }

    @Test
    void appendTurnKeepsOnlyTheNewestConfiguredNumberOfPairs() {
        repository.getOrCreate("session-window");
        repository.appendTurn("session-window", "问题1", "回答1", 2);
        repository.appendTurn("session-window", "问题2", "回答2", 2);
        repository.appendTurn("session-window", "问题3", "回答3", 2);

        assertThat(repository.findRecentMessages("session-window", 10))
                .containsExactly(
                        new ChatHistoryMessage("user", "问题2"),
                        new ChatHistoryMessage("assistant", "回答2"),
                        new ChatHistoryMessage("user", "问题3"),
                        new ChatHistoryMessage("assistant", "回答3")
                );
        assertThat(repository.findSession("session-window"))
                .get()
                .extracting(ChatSessionMetadata::messagePairCount)
                .isEqualTo(2);
    }

    @Test
    void clearKeepsSessionWhileDeleteRemovesIt() {
        repository.getOrCreate("session-clear");
        repository.appendTurn("session-clear", "问题", "回答", 6);

        assertThat(repository.clearHistory("session-clear")).isTrue();
        assertThat(repository.findSession("session-clear"))
                .get()
                .extracting(ChatSessionMetadata::messagePairCount)
                .isEqualTo(0);

        assertThat(repository.deleteSession("session-clear")).isTrue();
        assertThat(repository.findSession("session-clear")).isEmpty();
        assertThat(repository.deleteSession("session-clear")).isFalse();
    }

    @Test
    void missingSessionIsNotCreatedByReadOrClear() {
        assertThat(repository.findSession("missing")).isEmpty();
        assertThat(repository.clearHistory("missing")).isFalse();
        assertThat(repository.findRecentMessages("missing", 6)).isEqualTo(List.of());
    }
}
