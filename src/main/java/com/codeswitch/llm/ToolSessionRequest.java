package com.codeswitch.llm;

import java.util.List;

public record ToolSessionRequest(String system, List<ToolDefinition> tools, List<ChatTurn> history,
                                 String userMessage, ReasoningEffort effort, long maxTokens) {

    public ToolSessionRequest {
        tools = List.copyOf(tools);
        history = List.copyOf(history);
    }
}
