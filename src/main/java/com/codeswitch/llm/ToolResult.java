package com.codeswitch.llm;

/** Result returned to the model for one tool call; {@code content} is JSON text. */
public record ToolResult(String toolCallId, String content, boolean isError) {
}
