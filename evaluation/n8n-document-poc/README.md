# n8n document workflow pilot

An isolated, localhost-only n8n deployment for comparing workflow authoring and operations with the Flowable pilot. Data is stored in its own named volume. `document-ingestion-pilot.json` is the mock; `document-ingestion-live.json` connects to the local Aster backend.

## Start

```sh
cp n8n.env.example .env
# Set N8N_ENCRYPTION_KEY to a long random value before first start.
docker compose up -d
```

Open `http://127.0.0.1:5678/`. A local owner account is already initialized in this workspace's pilot volume; keep the service bound to localhost.

The live workflow accepts a multipart upload at `POST /webhook/aster-document-ingestion-v2` with a `file` field and an optional `query` field. It stores the document in OSS, parses through MinerU, polls indexing status until `READY`, verifies retrieval, and pauses for a human decision. Resume the n8n Wait node URL shown in execution data with an HTTP POST JSON body such as `{"approved":true,"reviewNote":"抽查通过"}`. Approval publishes the knowledge version; rejection stores `REJECTED` and the review note. The webhook immediately acknowledges receipt; inspect execution history for the document ID and Wait node resume URL.

Before activating the live workflow, create an n8n `HTTP Basic Auth` credential named `Aster Workflow Service` with the dedicated service email and password, then assign it to `获取工作流令牌`. This credential is used only at execution time to call `POST http://host.docker.internal:8123/api/auth/token`. The workflow passes the returned JWT in memory to the remaining requests; do not put the password or a fixed token in workflow JSON. The upload request is limited by the service role to tenant `default` and agent `test`. Use a synthetic document during evaluation.

The pilot was started and health-checked with n8n `2.44.6`. The live workflow's first execution showed that upload returns while indexing is still in progress, so status polling was added before retrieval. Execution #12 reached the Wait node after status `READY` with one PGVector chunk and a matching `7 years` result. Completion nodes refer back to the pre-wait retrieval node for the document ID. After login, the workflow list, node names/connections, and execution history were verified in the UI. At a 1280x720 browser viewport, the editor displays the connected flow and both approval branches on one canvas. The visual style is clean and substantially easier to scan than the Flowable 6.8 pilot; however, the navigation and execution panels reduce canvas space, so node labels are small at the fit-to-view scale. The execution view shows a successful run, elapsed time, payload size, execution ID, and node-level outcomes.

## Scope and comparison

n8n provides a node canvas, integrated workflow execution history, manual/webhook triggers, HTTP integrations, branching, retry/error workflows, and a Wait node for human intervention. In this pilot, the canvas and execution detail were visually checked after login, and a successful manual execution was confirmed. The Flowable 6.8 pilot has BPMN tasks, but its modeler/UI needs separate evaluation against the application runtime. The n8n Wait node is not a full human-task inbox: approval forms, user/group assignment, and audit semantics need a form/webhook path or additional workflow design. Automatic retry behavior is not configured or tested in this mock workflow.

This is an evaluation only. Before connecting real documents, add idempotency, bounded retries, and failure/compensation handling. n8n's Sustainable Use License permits internal business use but has restrictions around embedding/reselling n8n or exposing its features as a product; review current terms before making n8n part of a customer-facing product.

## Backend integration smoke test

On 2026-10-24, the repository backend was extended with a pending-review upload option (`autoPublish=false`), a document lifecycle status endpoint, and document-scoped vector retrieval verification. A synthetic DOCX was uploaded through the existing authenticated Aster API. The backend stored it in OSS, parsed it with MinerU, created one PGVector chunk, and reached `READY` without publishing. Scoped retrieval matched the expected “7 years” clause before publication. The publish endpoint changed the version to `ACTIVE`, and retrieval still matched afterward. The test document was deleted through the document API; its database record and associated vector/Oss data were cleaned up.

The backend has a dedicated `WORKFLOW` role, a Basic-auth token exchange endpoint for n8n Credentials, scoped upload/status/retrieval/publish/reject endpoints, and persisted rejection notes. `document-ingestion-live.json` wires those APIs together. Execution #12 verified polling to `READY`, retrieval of the expected clause, and approval to `ACTIVE` for document `d3ba005b-8147-4a45-8305-5e6668a0729f`. Execution #13 verified rejection and persisted its review note, leaving document `9bbbc5c5-434a-47c5-a9aa-f29d9a852d8d` in `REJECTED`. The `WORKFLOW` role intentionally has no document deletion privilege, so these synthetic records remain for administrator cleanup. Upload retries are intentionally not enabled because the backend upload API does not yet provide an idempotency key; retrying an ambiguous timeout could create a duplicate version.

Stop with `docker compose down`; the named volume remains. Do not use `down -v` unless intentionally discarding the evaluation data.
