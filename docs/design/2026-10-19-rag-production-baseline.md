# RAG Production Baseline

## Scope

This baseline makes the private knowledge path measurable and recoverable. It does not claim that every generated answer is factually correct; answer quality must be evaluated separately from retrieval quality.

## Retrieval path

```text
document -> parser -> structured chunks + provenance -> embedding/vector store
query -> vector recall + lexical recall -> reciprocal-rank fusion -> rerank -> context budget -> answer with citations
```

The lexical channel is deliberately lightweight and scoped to the tenant, agent, and ACTIVE lifecycle. It protects exact identifiers and policy terms that embeddings can miss. It is not a substitute for a Chinese-aware search service at large corpus sizes.

## Grounding contract

- Private documents are untrusted data, never system instructions.
- A retrieved context instructs the model to cite material facts as `[filename#chunkIndex]`.
- When evidence is absent, the model must say it cannot confirm the fact.
- `KnowledgeRetrievalOutcome` exposes `SUCCESS`, `NO_HIT`, `TIMEOUT`, `OVERLOADED`, or `FAILED`; callers must not treat every empty context as a knowledge miss.

## Ingestion recovery

New document indexing records attempts and retries transient failures up to three times. Previous ACTIVE versions are not automatically downgraded after a re-index failure. A production deployment should additionally export retry exhaustion and orphaned-object metrics.

## Offline evaluation

`src/test/resources/rag-evaluation/` contains only synthetic material. The Acme Nebula bonus policy is intentionally fictional and includes a no-answer case. The next operational step is to add 50-100 tenant-approved, redacted question/evidence pairs and run:

- Recall@K and MRR for retrieval;
- citation coverage for material claims;
- answer faithfulness and correct refusal rate;
- P50/P95 latency and embedding/rerank cost.

## Per-turn human review

Travel, Test, and Super Agent responses expose a retrieval inspector after each answer. It shows the query, vector and lexical candidates, RRF and rerank positions/scores, selected chunks, provenance excerpts, and stage timings. Candidate relevance judgments (`RELEVANT`, `PARTIAL`, `IRRELEVANT`) are stored in the assistant message trace and scoped to the owning user. This gives reviewers a repeatable first-pass labeling workflow; labels are not yet aggregated into a dashboard or exported evaluation set.
