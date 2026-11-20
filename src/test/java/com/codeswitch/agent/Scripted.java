package com.codeswitch.agent;

import com.codeswitch.llm.LlmClient;
import com.codeswitch.llm.ModelStep;
import com.codeswitch.llm.ToolCall;
import com.codeswitch.llm.ToolResult;
import com.codeswitch.llm.ToolSession;
import com.codeswitch.llm.ToolSessionRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/** A fake model that plays back scripted steps and records the tool results it receives. */
final class Scripted implements LlmClient {

    private final List<ModelStep> steps;
    final List<ToolResult> received = new ArrayList<>();
    int calls;

    Scripted(List<ModelStep> steps) {
        this.steps = steps;
    }

    static ToolCall call(String id, String name, String argsJson) throws Exception {
        return new ToolCall(id, name, new ObjectMapper().readTree(argsJson));
    }

    @Override
    public ToolSession startToolSession(ToolSessionRequest request) {
        return new ToolSession() {
            int i = 0;

            @Override
            public ModelStep next() {
                calls++;
                return steps.get(Math.min(i++, steps.size() - 1));
            }

            @Override
            public void submitToolResults(List<ToolResult> results) {
                received.addAll(results);
            }
        };
    }

    @Override
    public boolean isConfigured() {
        return true;
    }
}
