package com.codeswitch.agent;

import com.codeswitch.actions.ActionLedger;
import com.codeswitch.actions.ActionService;
import com.codeswitch.llm.ToolCall;
import com.codeswitch.llm.ToolDefinition;
import com.codeswitch.llm.ToolResult;
import com.codeswitch.ops.OpsSimulator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * The only operations the model can request. There is deliberately no "confirm", "execute" or shell tool:
 * writes become PENDING actions that a human must confirm through the API.
 */
@Component
public class AgentTools {

    private final OpsSimulator ops;
    private final ActionService actions;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    public AgentTools(OpsSimulator ops, ActionService actions) {
        this.ops = ops;
        this.actions = actions;
    }

    public List<ToolDefinition> definitions() {
        return List.of(
                new ToolDefinition("get_service_health",
                        "Read p99 latency, error rate and current version for a service over a time window (read-only).",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "service", Map.of("type", "string", "description", "Service name, e.g. checkout"),
                                        "window_minutes", Map.of("type", "integer", "minimum", 1, "maximum", 1440)),
                                "required", List.of("service", "window_minutes"))),
                new ToolDefinition("list_recent_deployments",
                        "List recent deployments of a service, newest last (read-only).",
                        Map.of("type", "object",
                                "properties", Map.of("service", Map.of("type", "string")),
                                "required", List.of("service"))),
                new ToolDefinition("propose_rollback",
                        "Propose rolling a service back to its previous version. This does NOT run it: a human must confirm. Refused unless the error rate is at least 2%.",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "service", Map.of("type", "string"),
                                        "reason", Map.of("type", "string", "maxLength", 300)),
                                "required", List.of("service", "reason"))),
                new ToolDefinition("propose_status_update",
                        "Propose a public status-page message. It is posted only after a human confirms.",
                        Map.of("type", "object",
                                "properties", Map.of("text", Map.of("type", "string", "maxLength", 500)),
                                "required", List.of("text"))));
    }

    /** Runs one requested tool. Never throws: failures are returned to the model as error results. */
    public ToolResult execute(ToolCall call) {
        try {
            Object payload = switch (call.name()) {
                case "get_service_health" -> health(call);
                case "list_recent_deployments" -> deployments(call);
                case "propose_rollback" -> proposeRollback(call);
                case "propose_status_update" -> proposeStatus(call);
                default -> throw new IllegalArgumentException("unknown tool: " + call.name());
            };
            return new ToolResult(call.id(), json.writeValueAsString(payload), false);
        } catch (ActionService.PolicyViolation | IllegalArgumentException e) {
            return new ToolResult(call.id(), "{\"error\":" + quote(e.getMessage()) + "}", true);
        } catch (Exception e) {
            return new ToolResult(call.id(), "{\"error\":\"tool failed\"}", true);
        }
    }

    private Object health(ToolCall call) {
        String service = required(call, "service");
        int window = call.arguments().path("window_minutes").asInt(15);
        return ops.metrics(service, window).orElseThrow(() -> new IllegalArgumentException("unknown service: " + service));
    }

    private Object deployments(ToolCall call) {
        String service = required(call, "service");
        return ops.deployments(service).orElseThrow(() -> new IllegalArgumentException("unknown service: " + service));
    }

    private Object proposeRollback(ToolCall call) {
        ActionLedger.Action a = actions.proposeRollback(required(call, "service"), required(call, "reason"), "AI agent");
        return pendingResponse(a);
    }

    private Object proposeStatus(ToolCall call) {
        return pendingResponse(actions.proposeStatusUpdate(required(call, "text"), "AI agent"));
    }

    private ObjectNode pendingResponse(ActionLedger.Action a) {
        ObjectNode n = json.createObjectNode();
        n.put("action_id", a.id());
        n.put("status", "PENDING_HUMAN_CONFIRMATION");
        n.put("description", a.detail());
        return n;
    }

    private static String required(ToolCall call, String field) {
        String v = call.arguments().path(field).asText("");
        if (v.isBlank()) throw new IllegalArgumentException("missing argument: " + field);
        return v;
    }

    private static String quote(String s) {
        return "\"" + (s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")) + "\"";
    }
}
