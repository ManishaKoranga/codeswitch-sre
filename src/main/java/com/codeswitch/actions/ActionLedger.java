package com.codeswitch.actions;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Audit trail of every proposed, confirmed, cancelled or failed operation. Entries are never deleted. */
@Component
public class ActionLedger {

    public enum Type { ROLLBACK, STATUS_UPDATE }

    public enum Status { PENDING, EXECUTED, CANCELLED, FAILED }

    public record Action(String id, Type type, String service, String detail, Status status,
                         String proposedBy, Instant proposedAt, Instant resolvedAt, String outcome) {

        Action resolved(Status newStatus, Instant at, String newOutcome) {
            return new Action(id, type, service, detail, newStatus, proposedBy, proposedAt, at, newOutcome);
        }
    }

    private final Clock clock;
    private final Map<String, Action> actions = new LinkedHashMap<>();

    public ActionLedger(Clock clock) {
        this.clock = clock;
    }

    public synchronized Action propose(Type type, String service, String detail, String proposedBy) {
        Action a = new Action(UUID.randomUUID().toString(), type, service, detail, Status.PENDING, proposedBy,
                clock.instant(), null, null);
        actions.put(a.id(), a);
        return a;
    }

    public synchronized Optional<Action> find(String id) {
        return Optional.ofNullable(actions.get(id));
    }

    public synchronized Action resolve(String id, Status status, String outcome) {
        Action current = actions.get(id);
        if (current == null) throw new IllegalArgumentException("unknown action " + id);
        Action updated = current.resolved(status, clock.instant(), outcome);
        actions.put(id, updated);
        return updated;
    }

    public synchronized List<Action> all() {
        return new ArrayList<>(actions.values());
    }
}
