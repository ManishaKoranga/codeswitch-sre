package com.codeswitch.actions;

import com.codeswitch.ops.OpsSimulator;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Every write goes through here. Proposals are checked against policy and stored as PENDING; they run
 * only when a human confirms them via the API. The AI agent has no path to confirm an action.
 */
@Service
public class ActionService {

    /** Policy: a rollback is only proposed when the error rate is at least this high. */
    public static final double ROLLBACK_MIN_ERROR_RATE_PCT = 2.0;

    private final OpsSimulator ops;
    private final ActionLedger ledger;

    public ActionService(OpsSimulator ops, ActionLedger ledger) {
        this.ops = ops;
        this.ledger = ledger;
    }

    public ActionLedger.Action proposeRollback(String service, String reason, String proposedBy) {
        var metrics = ops.metrics(service, 15).orElseThrow(() -> new IllegalArgumentException("unknown service: " + service));
        if (metrics.errorRatePct() < ROLLBACK_MIN_ERROR_RATE_PCT) {
            throw new PolicyViolation("Rollback refused: error rate is " + metrics.errorRatePct()
                    + "%, policy requires at least " + ROLLBACK_MIN_ERROR_RATE_PCT + "%");
        }
        String previous = ops.previousVersion(service)
                .orElseThrow(() -> new PolicyViolation("No previous version to roll back to"));
        return ledger.propose(ActionLedger.Type.ROLLBACK, service,
                "Roll back " + service + " from " + metrics.currentVersion() + " to " + previous + ". Reason: " + reason,
                proposedBy);
    }

    public ActionLedger.Action proposeStatusUpdate(String text, String proposedBy) {
        if (text == null || text.isBlank() || text.length() > 500) {
            throw new PolicyViolation("Status update must be between 1 and 500 characters");
        }
        return ledger.propose(ActionLedger.Type.STATUS_UPDATE, null, text.trim(), proposedBy);
    }

    /** Human confirmation. Executes the action exactly once. */
    public synchronized ActionLedger.Action confirm(String id, String confirmedBy) {
        ActionLedger.Action action = ledger.find(id).orElseThrow(() -> new IllegalArgumentException("unknown action"));
        if (action.status() != ActionLedger.Status.PENDING) {
            throw new PolicyViolation("Action is already " + action.status());
        }
        try {
            String outcome = switch (action.type()) {
                case ROLLBACK -> {
                    String version = ops.rollback(action.service());
                    yield "Rolled back " + action.service() + " to " + version + " (confirmed by " + confirmedBy + ")";
                }
                case STATUS_UPDATE -> {
                    ops.postStatus(action.detail());
                    yield "Status page updated (confirmed by " + confirmedBy + ")";
                }
            };
            return ledger.resolve(id, ActionLedger.Status.EXECUTED, outcome);
        } catch (RuntimeException e) {
            return ledger.resolve(id, ActionLedger.Status.FAILED, "Failed: " + e.getMessage());
        }
    }

    public ActionLedger.Action cancel(String id, String cancelledBy) {
        ActionLedger.Action action = ledger.find(id).orElseThrow(() -> new IllegalArgumentException("unknown action"));
        if (action.status() != ActionLedger.Status.PENDING) {
            throw new PolicyViolation("Action is already " + action.status());
        }
        return ledger.resolve(id, ActionLedger.Status.CANCELLED, "Cancelled by " + cancelledBy);
    }

    public List<ActionLedger.Action> all() {
        return ledger.all();
    }

    public Optional<ActionLedger.Action> find(String id) {
        return ledger.find(id);
    }

    public static class PolicyViolation extends RuntimeException {
        public PolicyViolation(String message) {
            super(message);
        }
    }
}
