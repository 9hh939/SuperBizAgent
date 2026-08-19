package org.example.service;

import org.example.agent.tool.QueryLogsTools;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Optional;

@Component
public class AgentToolSelector {

    private final Optional<QueryLogsTools> mockLogTools;
    private final Optional<ToolCallbackProvider> mcpToolCallbackProvider;

    public AgentToolSelector(
            Optional<QueryLogsTools> mockLogTools,
            Optional<ToolCallbackProvider> mcpToolCallbackProvider
    ) {
        this.mockLogTools = mockLogTools;
        this.mcpToolCallbackProvider = mcpToolCallbackProvider;
    }

    public Object[] methodTools(Object... baseTools) {
        if (mockLogTools.isEmpty()) {
            return baseTools;
        }

        Object[] selectedTools = Arrays.copyOf(baseTools, baseTools.length + 1);
        selectedTools[baseTools.length] = mockLogTools.get();
        return selectedTools;
    }

    public ToolCallback[] toolCallbacks() {
        if (mockLogTools.isPresent() || mcpToolCallbackProvider.isEmpty()) {
            return new ToolCallback[0];
        }
        return mcpToolCallbackProvider.get().getToolCallbacks();
    }
}
