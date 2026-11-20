package com.codeswitch.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.AssistantMessageParam;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Claude adapter using the official anthropic-java SDK. Reads ANTHROPIC_API_KEY or an `ant auth login` profile. */
@Component
public class AnthropicLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicLlmClient.class);

    private final String model;
    private final ObjectMapper json = new ObjectMapper();
    private final AnthropicClient client;

    public AnthropicLlmClient(@Value("${codeswitch.llm.model}") String model,
                              @Value("${codeswitch.llm.timeout}") Duration timeout,
                              @Value("${codeswitch.llm.max-retries}") int maxRetries) {
        this.model = model;
        this.client = create(timeout, maxRetries);
    }

    private static AnthropicClient create(Duration timeout, int maxRetries) {
        try {
            return AnthropicOkHttpClient.builder().fromEnv().timeout(timeout).maxRetries(maxRetries).build();
        } catch (RuntimeException e) {
            log.warn("Claude is not configured: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public boolean isConfigured() {
        return client != null;
    }

    @Override
    public ToolSession startToolSession(ToolSessionRequest request) {
        if (client == null) {
            throw new IllegalStateException("Claude is not configured on this server");
        }
        return new Session(request);
    }

    private final class Session implements ToolSession {

        private final ToolSessionRequest request;
        private final List<Tool> tools;
        private final List<MessageParam> messages = new ArrayList<>();

        Session(ToolSessionRequest request) {
            this.request = request;
            this.tools = request.tools().stream().map(AnthropicLlmClient::toSdkTool).toList();
            for (ChatTurn turn : request.history()) {
                messages.add(turn.role() == ChatTurn.Role.USER
                        ? MessageParam.ofUser(turn.text())
                        : MessageParam.ofAssistant(AssistantMessageParam.builder().content(turn.text()).build()));
            }
            messages.add(MessageParam.ofUser(request.userMessage()));
        }

        @Override
        public ModelStep next() {
            MessageCreateParams.Builder builder = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(request.maxTokens())
                    .system(request.system())
                    .outputConfig(OutputConfig.builder().effort(effort(request.effort())).build())
                    .messages(messages);
            tools.forEach(builder::addTool);
            Message response = client.messages().create(builder.build());
            messages.add(response.toParam());

            String text = response.content().stream()
                    .flatMap(b -> b.text().stream())
                    .map(TextBlock::text)
                    .collect(Collectors.joining("\n")).trim();
            List<ToolCall> calls = response.content().stream()
                    .flatMap(b -> b.toolUse().stream())
                    .map(this::toToolCall)
                    .toList();
            StopReason stop = response.stopReason().orElse(StopReason.END_TURN);
            ModelStep.Outcome outcome;
            if (stop.equals(StopReason.REFUSAL)) outcome = ModelStep.Outcome.REFUSED;
            else if (stop.equals(StopReason.MAX_TOKENS)) outcome = ModelStep.Outcome.TRUNCATED;
            else if (!calls.isEmpty()) outcome = ModelStep.Outcome.NEEDS_TOOL_RESULTS;
            else outcome = ModelStep.Outcome.FINISHED;
            return new ModelStep(text, calls, outcome);
        }

        @Override
        public void submitToolResults(List<ToolResult> results) {
            List<ContentBlockParam> blocks = results.stream()
                    .map(r -> ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                            .toolUseId(r.toolCallId())
                            .content(r.content())
                            .isError(r.isError())
                            .build()))
                    .toList();
            messages.add(MessageParam.ofUserOfContentBlockParams(blocks));
        }

        private ToolCall toToolCall(ToolUseBlock block) {
            JsonNode args = block._input().convert(JsonNode.class);
            return new ToolCall(block.id(), block.name(), args == null ? json.createObjectNode() : args);
        }
    }

    private static Tool toSdkTool(ToolDefinition def) {
        Map<String, Object> schema = def.inputSchema();
        Tool.InputSchema.Properties.Builder props = Tool.InputSchema.Properties.builder();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.getOrDefault("properties", Map.of());
        properties.forEach((k, v) -> props.putAdditionalProperty(k, JsonValue.from(v)));
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.getOrDefault("required", List.of());
        return Tool.builder()
                .name(def.name())
                .description(def.description())
                .inputSchema(Tool.InputSchema.builder()
                        .properties(props.build())
                        .required(required)
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .build())
                .build();
    }

    private static OutputConfig.Effort effort(ReasoningEffort effort) {
        return switch (effort) {
            case LOW -> OutputConfig.Effort.LOW;
            case MEDIUM -> OutputConfig.Effort.MEDIUM;
            case HIGH -> OutputConfig.Effort.HIGH;
        };
    }
}
