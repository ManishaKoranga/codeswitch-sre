package com.codeswitch.llm;

import java.util.List;

public record ModelStep(String text, List<ToolCall> toolCalls, Outcome outcome) {

    public enum Outcome { FINISHED, NEEDS_TOOL_RESULTS, TRUNCATED, REFUSED }
}
