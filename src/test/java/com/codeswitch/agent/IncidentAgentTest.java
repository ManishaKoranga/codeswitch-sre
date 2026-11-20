package com.codeswitch.agent;

import com.codeswitch.actions.ActionLedger;
import com.codeswitch.actions.ActionService;
import com.codeswitch.llm.ModelStep;
import com.codeswitch.ops.OpsSimulator;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IncidentAgentTest {

    private final Clock clock = Clock.systemUTC();
    private final OpsSimulator ops = new OpsSimulator(clock);
    private final ActionLedger ledger = new ActionLedger(clock);
    private final ActionService actions = new ActionService(ops, ledger);
    private final AgentTools tools = new AgentTools(ops, actions);

    @Test
    void agentProposesRollbackButNeverExecutesIt() throws Exception {
        Scripted model = new Scripted(List.of(
                new ModelStep("", List.of(Scripted.call("1", "get_service_health", "{\"service\":\"checkout\",\"window_minutes\":15}")),
                        ModelStep.Outcome.NEEDS_TOOL_RESULTS),
                new ModelStep("", List.of(Scripted.call("2", "propose_rollback",
                        "{\"service\":\"checkout\",\"reason\":\"error rate 4.2%\"}")), ModelStep.Outcome.NEEDS_TOOL_RESULTS),
                new ModelStep("I propose rolling checkout back to 2.13.4. Please confirm.", List.of(), ModelStep.Outcome.FINISHED)));

        var reply = new IncidentAgent(model, tools).handle("check checkout and roll back if errors are high");

        assertThat(reply.toolsUsed()).containsExactly("get_service_health", "propose_rollback");
        assertThat(ops.metrics("checkout", 15).orElseThrow().currentVersion()).as("production not changed").isEqualTo("2.14.0");
        assertThat(ledger.all()).hasSize(1);
        assertThat(ledger.all().get(0).status()).isEqualTo(ActionLedger.Status.PENDING);
    }

    @Test
    void humanConfirmationExecutesTheRollbackOnce() throws Exception {
        var proposal = actions.proposeRollback("checkout", "error rate 4.2%", "AI agent");
        var done = actions.confirm(proposal.id(), "on-call engineer");

        assertThat(done.status()).isEqualTo(ActionLedger.Status.EXECUTED);
        assertThat(ops.metrics("checkout", 15).orElseThrow().currentVersion()).isEqualTo("2.13.4");
        assertThat(ops.metrics("checkout", 15).orElseThrow().errorRatePct()).isLessThan(2.0);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> actions.confirm(proposal.id(), "again"))
                .isInstanceOf(ActionService.PolicyViolation.class);
    }

    @Test
    void rollbackRefusedWhenErrorRateBelowPolicy() {
        // Search has a 0.1% error rate, far below the 2% policy threshold.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> actions.proposeRollback("search", "slow", "AI agent"))
                .isInstanceOf(ActionService.PolicyViolation.class)
                .hasMessageContaining("policy requires at least 2.0%");
        assertThat(ledger.all()).isEmpty();
    }

    @Test
    void cancelledActionNeverRuns() {
        var proposal = actions.proposeRollback("checkout", "error rate 4.2%", "AI agent");
        actions.cancel(proposal.id(), "on-call engineer");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> actions.confirm(proposal.id(), "engineer"))
                .isInstanceOf(ActionService.PolicyViolation.class);
        assertThat(ops.metrics("checkout", 15).orElseThrow().currentVersion()).isEqualTo("2.14.0");
    }

    @Test
    void toolsDoNotIncludeAnyWayToConfirm() {
        List<String> names = tools.definitions().stream().map(d -> d.name()).toList();
        assertThat(names).containsExactlyInAnyOrder("get_service_health", "list_recent_deployments",
                "propose_rollback", "propose_status_update");
        assertThat(names).noneMatch(n -> n.contains("confirm") || n.contains("execute"));
    }

    @Test
    void toolErrorsReachTheModelAsErrorResults() throws Exception {
        var result = tools.execute(Scripted.call("9", "get_service_health", "{\"service\":\"nope\",\"window_minutes\":5}"));
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("unknown service");
    }

    @Test
    void agentLoopIsCapped() throws Exception {
        Scripted model = new Scripted(List.of(new ModelStep("", List.of(Scripted.call("x", "list_recent_deployments",
                "{\"service\":\"search\"}")), ModelStep.Outcome.NEEDS_TOOL_RESULTS)));
        var reply = new IncidentAgent(model, tools).handle("loop");
        assertThat(reply.stoppedEarly()).isTrue();
        assertThat(model.calls).isEqualTo(IncidentAgent.MAX_STEPS);
    }
}
