# Datalaga: Agentic Coding Memory Behind MCP

This repository is a working exploration of Datalevin as a backend for coding-agent memory exposed through an MCP server. The prototype focuses on a thin, honest vertical slice:

- structured EAV/Datalog memory with provenance
- MCP tools/resources that operate on high-value coding memory actions
- ingestion + normalization from realistic coding artifacts
- reproducible evaluation scenarios and report generation

The goal is to answer one question with evidence: should Datalevin be used for coding-agent memory behind MCP?

## Why Datalevin

Datalevin gives:

- ACID local transactions on LMDB
- Datalog queries for relationship-heavy retrieval
- full-text search over normalized memory bodies
- flexible EAV schema evolution for a prototype phase

That combination is promising for coding memory where facts and relationships matter more than document blobs.

## Repository Layout

- `src/datalaga/memory` schema, transaction logic, query logic
- `src/datalaga/mcp/server.clj` MCP stdio server (tools + resources)
- `src/datalaga/ingest.clj` ingestion and normalization pipeline
- `src/datalaga/eval.clj` evaluation harness + MCP smoke test
- `src/datalaga/inspect.clj` inspection CLI for debugging memory
- `src/datalaga/maintenance.clj` maintenance CLI for normalization/backfill
- `src/datalaga/memory/maintenance.clj` shared normalization engine
- `examples/seed-data.edn` synthetic but realistic linked coding-memory dataset
- `eval/report.md` generated evaluation report
- `docs/architecture.md` architecture details

## Local Setup

### Prerequisites

- Clojure CLI
- Java 21+
- On macOS x86_64 only: `libomp` (for Datalevin native dependency)

### Dependency + Native Handling

Use the wrapper script so Datalevin native loading works consistently:

- `./bin/clojure-with-dtlv ...`

The wrapper:

- ensures local Maven cache under `.m2/repository`
- extracts required Datalevin native libraries into `.native/dtlv` on macOS x86_64
- sets `DYLD_LIBRARY_PATH` appropriately

## Run the Prototype

Start MCP server:

```bash
./bin/start-mcp --db-path .data/memory --seed-file examples/seed-data.edn
```

Seed on startup only when requested:

```bash
./bin/start-mcp --db-path .data/memory --seed-file examples/seed-data.edn --seed-on-start
```

Run local demo/evaluation:

```bash
./bin/run-eval
```

Inspect memory directly:

```bash
./bin/inspect-memory summary project:phoenix-auth
./bin/inspect-memory task task:AUTH-142
./bin/inspect-memory symbol symbol:src/auth/session.clj#refresh-session
./bin/inspect-memory prior-decisions task:AUTH-160
./bin/inspect-memory --seed-before-run summary project:phoenix-auth
```

Run housekeeping normalization:

```bash
./bin/normalize-memory --project-id project:yoyo-evolve --mode dry_run
./bin/normalize-memory --project-id project:yoyo-evolve --mode apply --migration-id migration:v1
./bin/normalize-memory --seed-before-run --project-id project:yoyo-evolve --mode dry_run
```

## MCP Surface

Implemented tools:

- `ensure_project`
- `ensure_task`
- `list_projects`
- `upsert_code_entity`
- `search_entities`
- `remember_fact`
- `record_event`
- `record_tool_run`
- `record_error`
- `link_entities`
- `search_notes`
- `find_related_context`
- `get_symbol_memory`
- `get_task_timeline`
- `summarize_project_memory`
- `memory_query` (raw Datalevin Datalog query via EDN payload)
- `memory_pull` (entity pull with optional EDN pull pattern)
- `normalize_project_memory` (admin maintenance)

Notable behavior:

- `record_tool_run` infers a readable run name from `command` when `name` is omitted, sets `entity/status` from `exit_code` (`success` or `failed`), and can track replacement lineage via `supersedes_run_ids` / `retries_of_run_ids`.
- `record_tool_run` rejects compound shell commands (`&&`, `||`, `;`, `|`) so each run record maps to one executable command.
- `record_tool_run` accepts `touched_file_paths` in addition to `touched_file_ids`, auto-upserts `file:*` entities for those paths, and returns `normalized_touched_file_refs`.
- `record_event` accepts `subject_file_paths`, auto-upserts file entities for those paths, and returns `normalized_subject_file_refs`.
- `record_error` accepts `related_file_paths`, auto-upserts file entities for those paths, and returns `normalized_related_file_refs`.
- path-based file normalization now defaults to project-scoped file ids: `file:<project_id>:<path>`.
- `search_entities` applies path-intent ranking for path-like queries (for example `scripts/evolve.sh`), biasing file entities to the top when available.
- `search_entities` returns file-onboarding guidance on path-like file misses (`suggested_file_id`, `suggested_action=upsert_code_entity`, `suggested_arguments`) when `entity_type=file` and no file match exists.
- when a path-like file miss corresponds to an existing global `file:*` entity, `search_entities` also returns `existing_file_id` and `existing_file_project_id` to explain the miss and guide re-association/upsert.
- when `record_tool_run` is called without lineage fields, the server auto-links to the most recent matching run in the same project/task/session (`auto_lineage_inferred` in response).
- write tools enforce strict argument validation; unsupported fields are rejected with remediation data.
- unsupported-argument rejections may include `did_you_mean` remediation hints for common mistakes (for example `record_tool_run.status` -> use `exit_code`).
- tool errors include `error_type` and `retry_bootstrap_recommended` to separate validation fixes from true bootstrap retries.
- tool calls also enforce required argument presence (missing required args are rejected early).
- write transactions now prevalidate references before commit, preventing partial entity writes on missing refs.
- `ensure_task` normalizes statuses (`completed` -> `done`, `in-progress` -> `in_progress`) and active task views treat `done|completed|closed|cancelled` as non-active.
- `list_projects` is sorted by recent project activity (latest entity update/create), and each project brief includes `project/last-activity-at`.
- `record_error` defaults to `entity/status = open` unless provided explicitly.
- `link_entities` with `link_type = resolved_by` auto-marks the source error as `resolved` and attaches the resolver run as a reference.
- `link_entities` can also model cross-project service topology explicitly (for example `link_type = depends_on` or `link_type = calls_api` between `project:*` entities).
- `summarize_project_memory` and `memory://project/{project_id}/recent-failures` exclude errors already marked `resolved`/`closed`.
- `remember_fact` auto-normalizes `attributes.files` / `attributes.file_paths` into `file:*` refs, upserts missing file entities, and returns `normalized_file_refs`; `external_refs` (or `attributes.external_refs`) are normalized into `entity/external-refs`.
- `search_notes` supports `project_ids` for cross-project recall, and `find_related_context` accepts `project_ids` to broaden graph traversal beyond the anchor entity's project.
- `normalize_project_memory` supports project-scoped `dry_run|apply` housekeeping with operation filters:
  - `normalize_entity_types`
  - `backfill_error_resolution`
  - `link_run_supersession`
  - `scope_file_ids_by_project` (backfill project-scoped `file:<project_id>:<path>` IDs and refs)
  and writes a maintenance event on apply for auditability.

Exposed resources:

- `memory://project/{project_id}/summary`
- `memory://project/{project_id}/recent-failures`
- `memory://project/{project_id}/active-tasks`
- `memory://session/{session_id}/timeline`
- `memory://task/{task_id}/timeline`
- `memory://symbol/{symbol_id}/dossier`

## Example Memory Records

Seed data includes linked entities for:

- `Project`, `File`, `Symbol`
- `Task`, `Session`, `ToolRun`, `Error`, `Observation`
- `Decision`, `Patch`, `Note`, `Event`, `Link`

Example relation chains in the dataset:

- failing test tool run -> error -> observation -> decision -> patch -> follow-up notes
- task touched files -> prior decisions related files -> context recommendation
- file contains symbol -> symbol related failures/patches/decisions/notes

Custom entity type policy:

- core types are stored as-is (for example `project`, `task`, `error`)
- non-core types with no namespace are promoted to `custom/<type>` (for example `finding` -> `custom/finding`)
- non-core types with a namespace are kept as provided
- original custom type is retained in `entity/kind`

Code entity onboarding flow (for non-seeded repos):

1. `upsert_code_entity` for file/symbol anchors as you discover code structure.
2. `search_entities` to discover valid IDs before creating refs in `record_error`/`link_entities`.
3. `find_related_context` / `get_symbol_memory` become useful once anchors exist.

## Query Patterns Demonstrated

- exact entity lookup
- graph/EAV traversal around files/symbols/tasks
- full-text note/body search
- hybrid text + graph context retrieval

High-signal example:

- “What prior decisions touched files that this task is about to modify?”
  - implemented in `prior-decisions-for-task` via joins over task touched files and decision related files

## Tradeoffs Observed So Far

Strengths:

- graph/EAV retrieval materially outperforms plain text for anchored coding workflows
- provenance is explicit and inspectable
- memory model is readable and extensible

Pain points:

- integration glue is still required between MCP payloads and normalized entities
- full-text alone underperforms graph traversal for relationship-heavy coding questions

## Evaluation Output

Run `./bin/run-eval` to regenerate:

- `eval/report.md` with retrieval metrics by scenario
- MCP smoke test evidence (tools/resources reachable via stdio protocol)
- recommendation and rationale

## AGENTS.md Snippet (Enforce datalaga Usage)

Use this snippet in a repo-level `AGENTS.md` to enforce consistent memory behavior:

```md
## Required MCP Memory Policy (`datalaga`)

For all non-trivial coding tasks in this repository, you MUST use the `datalaga` MCP server.

### 1) Bootstrap
- At the start of work, call `list_projects`.
- Do not assume demo seed data exists; the server may run without `--seed-on-start`.
- If the current repo project is missing, call `ensure_project` with `project_id` and a short summary.
- If you will write task-scoped records (`task_id` on runs/events/errors/facts), call `ensure_task` first.
- `ensure_task` also updates an existing task’s mutable fields (`status`, `summary`, `priority`, `description`) when reused.

### 2) Read Before Acting
- Before edits or major analysis, call `summarize_project_memory`.
- When touching a symbol/file/task, load context first with:
  - `get_symbol_memory` (symbol-focused work),
  - `find_related_context` (file/entity neighborhood),
  - `get_task_timeline` (task-focused work), as applicable.
- Use `memory_pull` for targeted entity inspection when high-level tools are insufficient.
- Use `memory_query` for advanced recall/debugging (EDN Datalog), and prefer high-level read tools by default.

### 3) Write During/After Work
- Record command executions with `record_tool_run` (build/test/lint/tool output).
- Use one command per `record_tool_run` entry; do not combine commands with `&&` in a single run record.
- Do not send unknown or incomplete tool arguments. MCP calls with unsupported fields or missing required fields are rejected.
- If you rerun the same command for a task/session, keep `command` stable so lineage inference can chain retries automatically (or pass explicit `supersedes_run_ids` / `retries_of_run_ids`).
- Prefer `touched_file_paths` when file ids are unknown; the server will normalize and upsert file refs for run logging.
- Prefer `subject_file_paths` / `related_file_paths` when event/error references include files but file ids are unknown.
- Record failures with `record_error` and link to related runs/symbols.
- Persist key findings/decisions with `remember_fact` (or `record_event` for timeline milestones).
- When a fact references files, include `attributes.files` or `attributes.file_paths`; the server will auto-upsert `file:*` entities and attach them via `entity/refs`.
- Create explicit causality with `link_entities` when useful (e.g. error -> decision -> patch).

### 4) End-of-Task Memory Writeback
- Before final response, store a concise evaluation event containing:
  - what was checked,
  - what failed/passed,
  - risks,
  - follow-up opportunities.
- If memory write fails, inspect `error_type` / `retry_bootstrap_recommended` in the error data:
  - when `retry_bootstrap_recommended=true`, retry once after `list_projects`/`ensure_project` (and `ensure_task` if needed),
  - when `false` (for example validation errors), fix arguments and retry directly without bootstrap steps.

### 5) Scope and Quality
- Keep `project_id` consistent for all writes in a session.
- Prefer structured fields over blob text.
- Do not skip memory logging for convenience.
```

## License

MIT. See [LICENSE](LICENSE).
