package com.codeswitch.api;

import com.codeswitch.actions.ActionLedger;
import com.codeswitch.actions.ActionService;
import com.codeswitch.agent.IncidentAgent;
import com.codeswitch.agent.RcaWriter;
import com.codeswitch.llm.LlmClient;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api")
public class CommandController {

    public record CommandRequest(@NotBlank @Size(max = 2000) String text) {
    }

    public record CommandReply(String reply, List<String> toolsUsed, boolean stoppedEarly) {
    }

    public record ConfirmRequest(@NotBlank String confirmedBy) {
    }

    private final IncidentAgent agent;
    private final ActionService actions;
    private final RcaWriter rca;
    private final LlmClient llm;

    public CommandController(IncidentAgent agent, ActionService actions, RcaWriter rca, LlmClient llm) {
        this.agent = agent;
        this.actions = actions;
        this.rca = rca;
        this.llm = llm;
    }

    /** Voice-to-text clients send the transcript here; the agent treats it as the engineer's instruction. */
    @PostMapping("/command")
    public CommandReply command(@Valid @RequestBody CommandRequest body) {
        if (!llm.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI assistant is not configured");
        }
        IncidentAgent.Reply r = agent.handle(body.text());
        return new CommandReply(r.text(), r.toolsUsed(), r.stoppedEarly());
    }

    @GetMapping("/actions")
    public List<ActionLedger.Action> actions() {
        return actions.all();
    }

    @PostMapping("/actions/{id}/confirm")
    public ActionLedger.Action confirm(@PathVariable String id, @Valid @RequestBody ConfirmRequest body) {
        return actions.confirm(id, body.confirmedBy());
    }

    @PostMapping("/actions/{id}/cancel")
    @ResponseStatus(HttpStatus.OK)
    public ActionLedger.Action cancel(@PathVariable String id, @Valid @RequestBody ConfirmRequest body) {
        return actions.cancel(id, body.confirmedBy());
    }

    @GetMapping(value = "/rca", produces = MediaType.TEXT_MARKDOWN_VALUE)
    public String rca(@org.springframework.web.bind.annotation.RequestParam(defaultValue = "Checkout errors") String title) {
        return rca.draft(title);
    }
}
