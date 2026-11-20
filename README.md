# CodeSwitch SRE — Voice-driven incident commander

An AI incident commander for on-call engineers. An engineer describes what to check in plain language; the
assistant reads service metrics and deployment history, proposes safe actions (a rollback, a status-page
update), and drafts the post-incident review from the audit trail.

## Safety design (the important part)

- **The AI cannot change anything.** It has four tools: read health, read deployments, propose a rollback,
  propose a status update. There is no "confirm" or "execute" tool.
- **Every write is a pending action** that a human confirms through `POST /api/actions/{id}/confirm`.
  Each action runs exactly once; a second confirm is refused.
- **Policy in code, not in the prompt:** a rollback is refused unless the error rate is at least 2%, and only
  if a previous version exists.
- **Audit trail:** every proposal, execution, cancellation and failure is recorded and never deleted.
- **Tool results and engineer text are data**, not instructions that can change these rules.

## Important: what is simulated

The infrastructure is a **simulator** (`ops/OpsSimulator`) for the demo: metrics, deployments and the status
page are in memory. Nothing connects to a real system. A production version would implement the same
operations against Prometheus/Datadog, the deployment platform and a status-page API.

## Run it

```bash
export ANTHROPIC_API_KEY=...        # or an `ant auth login` profile
mvn spring-boot:run                  # http://localhost:8081
```

Try it:

```bash
curl -X POST localhost:8081/api/command -H 'Content-Type: application/json' \
  -d '{"text":"check checkout; if errors are above 2% propose a rollback and a status update"}'
curl localhost:8081/api/actions
curl -X POST localhost:8081/api/actions/<id>/confirm -H 'Content-Type: application/json' -d '{"confirmedBy":"on-call engineer"}'
curl "localhost:8081/api/rca?title=Checkout%20errors"
```

## Tests

```bash
mvn test
```

The tests use a scripted fake model and check the safety rules: no execution without confirmation, policy
refusal, cancellation, single execution, no confirm tool, and a capped agent loop.

## Status

- [x] Agent with read and propose tools, Claude via the official SDK
- [x] Human confirmation gate, policy checks, audit trail
- [x] Simulated checkout incident end to end, including recovery after rollback
- [x] Post-incident review draft
- [ ] Voice input (browser recording + speech-to-text) — planned: reuse the LaunchMate whisper adapter
- [ ] Real integrations (metrics, deployments, status page)
