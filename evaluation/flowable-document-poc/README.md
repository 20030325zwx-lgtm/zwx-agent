# Flowable document workflow evaluation

An isolated proof of concept for evaluating Flowable with the repository's Spring Boot 3.4 / Java 21 stack. It deliberately uses H2 and mock service tasks; it does not connect to the application database, MinerU, OSS, or PGVector.

The BPMN flow models parse -> chunk -> index -> retrieval verification -> human approval -> publish/reject. It demonstrates persistent process state, a user task pause, and continuation after approval. The Java delegates only record which mock step ran.

## Embedded engine POC

```sh
mvn test
mvn spring-boot:run
```

The sample API listens on `http://127.0.0.1:8137`:

```sh
curl -X POST http://127.0.0.1:8137/poc/document-workflows \
  -H 'Content-Type: application/json' \
  -d '{"documentId":"doc-123","tenantId":"tenant-a"}'
curl http://127.0.0.1:8137/poc/document-workflows/<processInstanceId>/tasks
curl -X POST http://127.0.0.1:8137/poc/document-workflows/tasks/<taskId>/complete \
  -H 'Content-Type: application/json' \
  -d '{"approved":true,"reviewNote":"retrieval checked"}'
```

The process definition is `src/main/resources/processes/document-indexing.bpmn20.xml`, suitable for visual inspection in any BPMN 2.0 modeler. This tests Flowable 7.2 in the application's Spring Boot generation.

## Evaluation boundary

The embedded Flowable 7.2 POC verifies process execution and human-task pause/resume with mock delegates. The separate Flowable UI 6.8 pilot below verifies the visual applications and its own Process REST API. They are intentionally independent because the 6.8 UI is not compatible as a drop-in front end for the 7.2 embedded engine. Neither currently invokes the main application's real document ingestion steps.

## Visual UI check (2026-10-23)

The official `flowable/flowable-ui:6.8.0` image was started separately on localhost port 8181. The login, Modeler process list/editor, BPMN palette/canvas, Task app, Admin app, and IDM app all loaded. The Modeler exposed BPMN start events, service tasks, user tasks, gateways, end events, validation, import, and save controls. The Task app opened its task inbox.

This validates that Flowable has the drag-and-drop visual design and human-task UI experience. It does **not** validate compatibility with this POC's Flowable 7.2 engine: the UI image bundles Flowable 6.8.0, Spring Boot 2.7.6, and Java 11, and runs its own engine/application stack. It is not a drop-in UI for the Spring Boot 3.4 / Flowable 7.2 application. The image was run on localhost only and stopped after inspection.

For a first integrated pilot, this directory now also provides a separate Flowable UI 6.8 service with its own PostgreSQL database and REST boundary. It can be used to model and operate a pilot workflow without changing the main application's upload/index path. This is a parallel pilot, not yet an upload-triggered integration: the main application's current endpoint directly starts indexing, and must not be switched until the REST API, identity/tenant mapping, service-task callbacks, retries and rollback behavior have been verified.

## Start the visual pilot

```sh
cp flowable.env.example .env
# Set unique local FLOWABLE_DB_PASSWORD and FLOWABLE_ADMIN_PASSWORD values in .env.
docker compose up -d
```

Open `http://127.0.0.1:8181/flowable-ui/`. Use the configured admin account. The Modeler app lets you create/import BPMN models; the Task app lists and completes user tasks; Admin and IDM provide instance and identity administration. Keep this pilot bound to localhost. Before exposing it beyond this machine, configure TLS, an external identity provider, a non-default encryption secret, backup/retention and network access controls.

The engine REST API is provided under `/flowable-ui/process-api` (verified against the running 6.8.0 image). For example, query deployed definitions:

```sh
curl -u "$FLOWABLE_ADMIN_USER:$FLOWABLE_ADMIN_PASSWORD" \
  http://127.0.0.1:8181/flowable-ui/process-api/repository/process-definitions
```

The `pilot-workflow.sh` helper can deploy `document-review.bpmn20.xml`, start the `asterDocumentReview` process with `reviewer`, `documentId`, and `tenantId` variables, list its task, and complete the task with `approved` and `reviewNote` values. The sample process intentionally starts at the human review step: parsing, chunking, indexing, and retrieval remain in the main application and are not invoked by this standalone UI deployment yet.

```sh
./pilot-workflow.sh deploy
./pilot-workflow.sh start demo-doc tenant-a admin
./pilot-workflow.sh tasks <processInstanceId>
./pilot-workflow.sh complete <taskId> true '检索结果抽查通过'
```

The Task UI displays the assigned task and supports completing it, but this sample does not define a Flowable form; to choose the approval branch and save a review note, complete through the REST helper. Building a user form and mapping application users/groups is part of the next integration increment.

In this local pilot, `tenantId` is only a process variable and does not enable Flowable engine tenant isolation. The bootstrap administrator is global. Do not use this deployment for multi-user or production data until tenant scoping, per-user authentication/authorization, audit policy, and an actual task form are implemented.

The Modeler and engine are the same 6.8 installation in this pilot, so a process saved/deployed in its UI runs on that UI's engine. The Spring Boot 3.4 / Flowable 7.2 embedded POC remains separate and is not connected to this 6.8 UI. The official UI image uses x86_64 emulation on Apple Silicon.

Stop with `docker compose down`. Database data remains in the named `flowable-data` volume; do not use `down -v` unless intentionally discarding the pilot database.
