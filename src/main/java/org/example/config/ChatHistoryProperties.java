package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "chat.history")
public class ChatHistoryProperties {

    private int maxMessagePairs = 6;

    public int getMaxMessagePairs() {
        return maxMessagePairs;
    }

    public void setMaxMessagePairs(int maxMessagePairs) {
        if (maxMessagePairs < 1) {
            throw new IllegalArgumentException("chat.history.max-message-pairs must be at least 1");
        }
        this.maxMessagePairs = maxMessagePairs;
    }
}
