package org.example.service;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.example.model.ChatHistoryMessage;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ChatServiceTest {

    @Test
    void systemPromptIncludesPersistedUserAndAssistantMessages() {
        ChatService service = new ChatService();

        String prompt = service.buildSystemPrompt(List.of(
                new ChatHistoryMessage("user", "数据库变慢了"),
                new ChatHistoryMessage("assistant", "先检查连接池")
        ));

        assertThat(prompt).contains(
                "--- 对话历史 ---",
                "用户: 数据库变慢了",
                "助手: 先检查连接池",
                "--- 对话历史结束 ---"
        );
    }

    @Test
    void standardChatModelUsesConfiguredApiAndChatOptions() {
        ChatService service = new ChatService();
        DashScopeApi configuredApi = mock(DashScopeApi.class);
        ReflectionTestUtils.setField(service, "dashScopeApi", configuredApi);

        DashScopeChatModel model = service.createStandardChatModel();

        assertThat(ReflectionTestUtils.getField(model, "dashscopeApi")).isSameAs(configuredApi);
        DashScopeChatOptions options = model.getDashScopeChatOptions();
        assertThat(options.getTemperature()).isEqualTo(0.7);
        assertThat(options.getMaxTokens()).isEqualTo(2000);
        assertThat(options.getTopP()).isEqualTo(0.9);
    }

    @Test
    void aiOpsChatModelUsesConfiguredApiAndAiOpsOptions() {
        ChatService service = new ChatService();
        DashScopeApi configuredApi = mock(DashScopeApi.class);
        ReflectionTestUtils.setField(service, "dashScopeApi", configuredApi);

        DashScopeChatModel model = service.createAiOpsChatModel();

        assertThat(ReflectionTestUtils.getField(model, "dashscopeApi")).isSameAs(configuredApi);
        DashScopeChatOptions options = model.getDashScopeChatOptions();
        assertThat(options.getTemperature()).isEqualTo(0.3);
        assertThat(options.getMaxTokens()).isEqualTo(8000);
        assertThat(options.getTopP()).isEqualTo(0.9);
    }
}
