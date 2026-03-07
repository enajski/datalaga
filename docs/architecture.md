# Architecture

## 1. Memory Model

The store uses Datalevin EAV entities with explicit coding-memory types:

- `:project`, `:file`, `:symbol`
- `:task`, `:session`, `:tool-run`, `:error`, `:observation`
- `:decision`, `:patch`, `:note`, `:event`, `:link`

Core schema goals:

- append-oriented event capture (`event`, `tool-run`, `error`, `observation`, `note`)
- explicit provenance (`:entity/source`, `:entity/source-ref`, timestamps)
- explicit refs for first-class relationships (not inferred from text)

Relationship attributes include:

- `:file/contains-symbols`
- `:task/touched-files`, `:task/related-symbols`
- `:patch/modified-files`, `:patch/modified-symbols`, `:patch/decision`, `:patch/tool-run`
- `:error/tool-run`, `:error/related-symbols`
- `:note/refers-to`, `:event/subjects`
- `:link/from`, `:link/to`, `:link/evidence`, `:link/type`

The `:entity/body` attribute is full-text indexed and stores normalized searchable text for hybrid retrieval.

## 2. Storage and Transaction Layer

`memory/store.clj` provides:

- connection lifecycle (`open-conn`, `close!`, `db`)
- merge/upsert behavior by stable `:entity/id`
- single Datalevin transaction per write batch (scalar + ref attrs together)
- ref prevalidation before writes to fail fast on missing referenced entities and avoid partial commits
- pull/query helpers for project scans and entity fetches
- full-text helper (`search-body`) used by retrieval flows

This keeps write logic explicit and debuggable, while preserving a stable identity model required by MCP tools.

## 3. Ingestion and Normalization Flow

`ingest.clj` converts artifact-level records into normalized Datalevin entities.

Input artifacts:

- project/file/symbol metadata
- task/session definitions
- tool outputs and failures
- decisions, patches, notes
- timeline events and typed links

Normalization behavior:

- each artifact type maps to a deterministic entity shape
- common fields flow through a shared `base-entity` function
- ref IDs remain explicit IDs and are resolved in transaction layer
- searchable `:entity/body` is generated per artifact

Seed flow:

1. load `examples/seed-data.edn`
2. normalize artifact records into entity maps
3. transact entities into Datalevin

## 4. MCP Tool Design

`mcp/server.clj` implements a compact stdio JSON-RPC MCP server.

Implemented methods:

- `initialize`, `tools/list`, `tools/call`
- `resources/list`, `resources/templates/list`, `resources/read`

Tool behavior:

- bootstrap/discovery:
  - `ensure_project`, `ensure_task`, `list_projects`, `search_entities`
- code-entity onboarding:
  - `upsert_code_entity` for file/symbol anchors in non-seeded repos
- write tools map to explicit entity transactions:
  - `remember_fact`, `record_event`, `record_tool_run`, `record_error`, `link_entities`
- admin maintenance tool:
  - `normalize_project_memory` (project-scoped dry-run/apply normalization and backfill)
- read tools map to high-level query functions:
  - `search_notes`, `find_related_context`, `get_symbol_memory`, `get_task_timeline`, `summarize_project_memory`
  - plus generic read primitives: `memory_query` (EDN Datalog) and `memory_pull` (EDN pull pattern)

Design principle:

- expose coding-memory operations, not raw Datalevin internals
- return actionable remediation hints when project references are missing
- avoid implicit data mutation at process startup; seeding is opt-in (`--seed-on-start`)

Operational semantics implemented in tools:

- `record_tool_run` infers a friendly run name from the command when omitted, sets `:entity/status` based on `exit_code`, and stores supersession lineage (`:tool-run/supersedes`, `:tool-run/retries-of`).
- `record_tool_run` can auto-infer supersession/retry lineage when omitted by linking to the latest matching command in the same project/task/session scope.
- write tools validate allowed arguments strictly to prevent silent acceptance of unsupported fields.
- tool calls validate required argument presence to fail fast with clear remediation before transaction attempts.
- `ensure_task` is idempotent for creates and supports updating mutable task metadata (status/summary/priority/description) on existing tasks within the same project.
- `record_error` defaults `:entity/status` to `:open` (or accepts explicit status).
- `link_entities` with `link_type = resolved_by` marks the source error as `:resolved` and records the resolving entity in refs.
- failure-focused views (`recent-failures`, `project-summary`) exclude errors with status `:resolved` or `:closed`.
- `remember_fact` normalizes `attributes.files` / `attributes.file_paths` into canonical `file:*` refs and auto-upserts file entities for consistent graph linking.
- `normalize_project_memory` provides project-scoped housekeeping with operation filters (`normalize_entity_types`, `backfill_error_resolution`, `link_run_supersession`), `max_changes` safety cap, and `migration_id` idempotency via migration event writeback.

Entity type policy:

- known core types are accepted directly
- unknown unqualified types are normalized to `custom/<type>`
- unknown qualified types are preserved
- original custom type intent is retained in `:entity/kind`

## 5. Retrieval Strategy

`memory/queries.clj` supports multiple retrieval styles:

- exact lookup by stable IDs/names/paths
- ranked exact/hybrid results prioritize code-like exact matches (`entity/id`, `entity/path`, symbol qualified name) before broad text matches
- graph traversal around an anchor entity (`find-related-context`)
- task/symbol dossier assembly (`get-task-timeline`, `get-symbol-memory`)
- full-text retrieval from `:entity/body`
- hybrid retrieval combining text hits and graph neighbors

Notable Datalog-style win:

- `prior-decisions-for-task` joins current task touched files with historical decision related files to retrieve relevant prior decisions.

## 6. Evaluation Harness

`eval.clj` runs reproducible scenarios and compares retrieval modes:

- exact entity lookup
- graph/EAV traversal
- full-text search
- hybrid text + graph

It also runs an MCP smoke test by starting `./bin/start-mcp` and calling:

- `initialize`
- `tools/list`
- `tools/call` (`get_task_timeline`)
- `resources/read` (project summary resource)

Outputs:

- machine-readable summary in stdout
- markdown report in `eval/report.md`

## 7. Operational Notes

Observed in this environment:

- Datalevin `0.10.7` lacked a usable `macosx-x86_64` native path
- prototype uses `0.9.27` with wrapper-managed native extraction and `libomp`
- LMDB operations require running outside strict sandbox constraints

This is the main integration caveat in an otherwise effective local-first prototype.

Maintenance strategy:

- normalization/backfill logic is implemented once in `memory/maintenance.clj`
- exposed through MCP (`normalize_project_memory`) for auditable remote operation
- also exposed as local CLI (`./bin/normalize-memory`) for deterministic housekeeping in scripts/ops workflows
