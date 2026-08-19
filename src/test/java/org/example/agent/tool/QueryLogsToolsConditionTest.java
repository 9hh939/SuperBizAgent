package org.example.agent.tool;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class QueryLogsToolsConditionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(QueryLogsTools.class);

    @Test
    void doesNotRegisterMockLogToolsWhenMockModeIsDisabled() {
        contextRunner
                .withPropertyValues("cls.mock-enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(QueryLogsTools.class));
    }

    @Test
    void registersMockLogToolsWhenMockModeIsEnabled() {
        contextRunner
                .withPropertyValues("cls.mock-enabled=true")
                .run(context -> assertThat(context).hasSingleBean(QueryLogsTools.class));
    }
}
