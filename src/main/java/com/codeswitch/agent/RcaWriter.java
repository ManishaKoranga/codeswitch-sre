package com.codeswitch.agent;

import com.codeswitch.actions.ActionLedger;
import com.codeswitch.actions.ActionService;
import com.codeswitch.ops.OpsSimulator;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Builds a post-incident review draft from the audit trail. Deterministic and sourced only from recorded
 * actions; the engineer edits the root cause and follow-ups before publishing.
 */
@Component
public class RcaWriter {

    private final ActionService actions;
    private final OpsSimulator ops;

    public RcaWriter(ActionService actions, OpsSimulator ops) {
        this.actions = actions;
        this.ops = ops;
    }

    public String draft(String title) {
        List<ActionLedger.Action> timeline = actions.all().stream()
                .sorted(Comparator.comparing(ActionLedger.Action::proposedAt)).toList();
        StringBuilder md = new StringBuilder();
        md.append("# Post-incident review: ").append(title).append("\n\n");
        md.append("> Draft generated from the action audit trail. Edit the root cause and follow-ups before publishing.\n\n");
        md.append("## Timeline of actions\n\n");
        if (timeline.isEmpty()) md.append("No actions were proposed.\n");
        for (ActionLedger.Action a : timeline) {
            md.append("- ").append(stamp(a.proposedAt())).append(" · proposed by ").append(a.proposedBy())
                    .append(" · ").append(a.type()).append(" — ").append(a.detail()).append(" · status: ")
                    .append(a.status());
            if (a.outcome() != null) md.append(" (").append(a.outcome()).append(")");
            md.append("\n");
        }
        md.append("\n## Status page messages\n\n");
        List<String> status = ops.statusPage();
        if (status.isEmpty()) md.append("None.\n");
        status.forEach(s -> md.append("- ").append(s).append("\n"));
        md.append("\n## Root cause\n\nTo be completed by the on-call engineer.\n");
        md.append("\n## Follow-ups\n\n- [ ] To be completed.\n");
        return md.toString();
    }

    private static String stamp(Instant i) {
        return i.toString();
    }
}
