package org.example.service;

import org.example.agent.tool.QueryLogsTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentToolSelectorTest {

    @Test
    void includesMockLogToolWhenMockModeIsActive() {
        QueryLogsTools mockLogTool = new QueryLogsTools();
        AgentToolSelector selector = new AgentToolSelector(
                Optional.of(mockLogTool), Optional.empty());
        Object baseTool = new Object();

        assertThat(selector.methodTools(baseTool))
                .containsExactly(baseTool, mockLogTool);
    }

    @Test
    void hidesMcpCallbacksWhenMockModeIsActive() {
        ToolCallback mcpCallback = mock(ToolCallback.class);
        AgentToolSelector selector = new AgentToolSelector(
                Optional.of(new QueryLogsTools()),
                Optional.of(ToolCallbackProvider.from(mcpCallback)));

        assertThat(selector.toolCallbacks()).isEmpty();
    }

    @Test
    void leavesMethodToolsUnchangedWhenMockModeIsInactive() {
        AgentToolSelector selector = new AgentToolSelector(Optional.empty(), Optional.empty());
        Object firstTool = new Object();
        Object secondTool = new Object();

        assertThat(selector.methodTools(firstTool, secondTool))
                .containsExactly(firstTool, secondTool);
    }

    @Test
    void exposesMcpCallbacksWhenMockModeIsInactive() {
        ToolCallback mcpCallback = mock(ToolCallback.class);
        AgentToolSelector selector = new AgentToolSelector(
                Optional.empty(), Optional.of(ToolCallbackProvider.from(mcpCallback)));

        assertThat(selector.toolCallbacks()).containsExactly(mcpCallback);
    }

    @Test
    void returnsNoCallbacksWhenMcpProviderIsUnavailable() {
        AgentToolSelector selector = new AgentToolSelector(Optional.empty(), Optional.empty());

        assertThat(selector.toolCallbacks()).isEmpty();
    }
}
