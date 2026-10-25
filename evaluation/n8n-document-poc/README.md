# n8n document workflow pilot

An isolated, localhost-only n8n deployment for comparing workflow authoring and operations with the Flowable pilot. Data is stored in its own named volume. `document-ingestion-pilot.json` is the mock; `document-ingestion-live.json` connects to the local Aster backend; `document-review-watch.json` listens for pending-review events pushed by the backend (see `docs/design/2026-10-25-knowledge-review-event-notify.md`).

## Document review watch workflow (backend event push)

`document-review-watch.json` makes UI-uploaded documents visible in n8n: one execution = one file = one case, and the canvas shows the whole lifecycle. On upload the backend dispatches a `knowledge.review.pending` event (`N8nReviewEventNotifier`, status INDEXING, for documents whose idempotency key is not `n8n-` prefixed). The workflow registers a phase resume URL (`resume-info`), waits up to 30 minutes, and the backend wakes it once indexing completes (`READY`). It then queries `workflow-status` — which now carries `parseDurationMs/vectorDurationMs/chunkCount` (V24) — and either finalizes directly (auto-published docs) or renders the three phase nodes ① 文档解析 ② 结构化切片 ③ 向量化入库 with the real timings before registering the approval resume URL and waiting up to 7 days. Approval still happens in the backend; publish/reject wakes the case for finalization. The status-first design self-heals missed registrations and stale watchers.

Import and activate (new workflow, generates a fresh id):

```sh
docker cp document-review-watch.json aster-n8n-pilot-n8n-1:/tmp/
docker exec aster-n8n-pilot-n8n-1 n8n import:workflow --input=/tmp/document-review-watch.json --projectId=U7PVZEh5wHym3aau
# read the printed new workflow id, unpublish the superseded one, then:
docker exec aster-n8n-pilot-n8n-1 n8n publish:workflow --id=<newId>
docker restart aster-n8n-pilot-n8n-1
```

The workflow reuses the existing `Aster Workflow Service` Basic credential. Review events require `app.notify.n8n.review-webhook-url` on the backend (pointing at `http://127.0.0.1:5678/webhook/aster-document-review-notify`); leave it empty to disable the push. Current live id: `lg86ItRGcoPJYgij` (v2; v1 `hqrJUWFuFM1SgQhT` unpublished). Known edge: a FAILED document whose retry later succeeds has no open case at that point, so the retry itself produces no case.

## Start

```sh
cp n8n.env.example .env
# Set N8N_ENCRYPTION_KEY to a long random value before first start.
docker compose up -d
```

Open `http://127.0.0.1:5678/`. A local owner account is already initialized in this workspace's pilot volume; keep the service bound to localhost.

The live workflow accepts a multipart upload at `POST /webhook/aster-document-ingestion-v2` with a `file` field and an optional `query` field. It stores the document in OSS, parses through MinerU, then polls indexing status on a bounded loop: a Set node keeps `pollAttempts` across iterations and an IF gate ends the execution through a `Stop and Error` node once the backend reports `FAILED` or 90 polls (≈15 minutes at 10 s intervals) pass without `READY`. The failure message carries documentId, poll count and current status. Past `READY` it verifies retrieval, registers the Wait resume URL with the backend (`POST .../resume-info`, secret, never echoed back), and pauses for a human decision.

Approval is decided in the backend, not in n8n: a REVIEWER user (assigned by an admin via `POST /api/ai/agent-reviewers`) calls `POST /api/ai/agent-knowledge/documents/{id}/publish|reject` with their own JWT. The backend validates the assignment, updates the document, writes an append-only audit row (`GET .../review-log`), and then triggers the stored resume URL itself so n8n finalizes (the workflow queries the final status and ends; it no longer calls publish/reject). The Wait resume URL is a capability secret held only by the backend; n8n workflow ids change on every CLI re-import — current live id: `d7AZrTUp2o1QxTLG` on n8n `2.45.3`.

The workflow intentionally contains no Code nodes: on n8n's internal task runner, Code-node task requests intermittently expire (`requestExpired`) and executions hang in `running`. All logic uses native nodes (Set/IF/Stop and Error/Wait/HTTP) that run in-process. Updating the workflow is done by CLI re-import (`n8n import:workflow` creates a new workflow id, then `n8n unpublish:workflow`/`n8n publish:workflow` to swap) followed by a container restart; superseded copies are archived. Backend HTTP calls to n8n must force HTTP/1.1 — see `docs/bugs/bug-2026-10-24-n8n-java-httpclient-h2c-hang.txt`.

Before activating the live workflow, create an n8n `HTTP Basic Auth` credential named `Aster Workflow Service` with the dedicated service email and password, then assign it to `获取工作流令牌`. This credential is used only at execution time to call `POST http://host.docker.internal:8123/api/auth/token`. The workflow passes the returned JWT in memory to the remaining requests; do not put the password or a fixed token in workflow JSON. The upload request is limited by the service role to tenant `default` and agent `test`. Use a synthetic document during evaluation.

The pilot was started and health-checked with n8n `2.44.6`. The live workflow's first execution showed that upload returns while indexing is still in progress, so status polling was added before retrieval. Execution #12 reached the Wait node after status `READY` with one PGVector chunk and a matching `7 years` result. Completion nodes refer back to the pre-wait retrieval node for the document ID. After login, the workflow list, node names/connections, and execution history were verified in the UI. At a 1280x720 browser viewport, the editor displays the connected flow and both approval branches on one canvas. The visual style is clean and substantially easier to scan than the Flowable 6.8 pilot; however, the navigation and execution panels reduce canvas space, so node labels are small at the fit-to-view scale. The execution view shows a successful run, elapsed time, payload size, execution ID, and node-level outcomes.

## Scope and comparison

n8n provides a node canvas, integrated workflow execution history, manual/webhook triggers, HTTP integrations, branching, retry/error workflows, and a Wait node for human intervention. In this pilot, the canvas and execution detail were visually checked after login, and a successful manual execution was confirmed. The Flowable 6.8 pilot has BPMN tasks, but its modeler/UI needs separate evaluation against the application runtime. The n8n Wait node is not a full human-task inbox: approval forms, user/group assignment, and audit semantics need a form/webhook path or additional workflow design. Automatic retry behavior is not configured or tested in this mock workflow.

This is an evaluation only. Before connecting real documents, add idempotency, bounded retries, and failure/compensation handling. n8n's Sustainable Use License permits internal business use but has restrictions around embedding/reselling n8n or exposing its features as a product; review current terms before making n8n part of a customer-facing product.

## Backend integration smoke test

On 2026-10-24, the repository backend was extended with a pending-review upload option (`autoPublish=false`), a document lifecycle status endpoint, and document-scoped vector retrieval verification. A synthetic DOCX was uploaded through the existing authenticated Aster API. The backend stored it in OSS, parsed it with MinerU, created one PGVector chunk, and reached `READY` without publishing. Scoped retrieval matched the expected “7 years” clause before publication. The publish endpoint changed the version to `ACTIVE`, and retrieval still matched afterward. The test document was deleted through the document API; its database record and associated vector/Oss data were cleaned up.

The backend has a dedicated `WORKFLOW` role, a Basic-auth token exchange endpoint for n8n Credentials, scoped upload/status/retrieval endpoints, and a review governance layer (V23): REVIEWER role + per-agent assignment table, append-only audit log, backend-held resume URLs. `document-ingestion-live.json` wires those APIs together. The governance chain was verified end to end on 2026-10-24: admin assigned `smokeuser` to `default/test` (auto-promoted to REVIEWER), an upload paused at human review with the resume URL registered, publish as `smokeuser` produced `ACTIVE` with `SUBMIT`+`PUBLISH` audit rows and the n8n execution finalizing itself (`success`), reject produced `REJECTED` with the note in the audit trail, and plain `USER` identities were denied (403) on both publish and resume-info. Upload retries are enabled (`maxTries=3`) with `Idempotency-Key: n8n-{{ $execution.id }}` backed by the V22 idempotency key.

On 2026-10-24 the bounded polling loop was added and verified: a cap=1 test variant produced execution #25 ending in `error` with the explicit message `索引轮询超时：documentId=… 轮询 1 次仍未 READY（当前状态 INDEXING）`; the corrected live workflow then ran upload→READY→human review→rejection as execution #26 ending in `success` with the review note persisted. An earlier wiring mistake (timeout gate connected to the READY branch instead of the not-ready branch) kept a parallel polling chain running forever, which is also why resumed executions previously hung in `running` until the next restart marked them `crashed`.

The backend upload API now honors an `Idempotency-Key` header (V22 migration: scoped partial unique index). Retries with the same key return the original document instead of creating a duplicate version; see `docs/design/2026-10-24-knowledge-upload-idempotency.md`. The live workflow sends `Idempotency-Key: n8n-{{ $execution.id }}` and has node-level retry enabled on the token and upload nodes (`maxTries=3`); execution #28 verified the full chain with the key stored as `n8n-28`, approval publishing to `ACTIVE`, and the execution ending `success`. All synthetic verification documents were deleted by an administrator afterwards.

Stop with `docker compose down`; the named volume remains. Do not use `down -v` unless intentionally discarding the evaluation data.
