# Datalevin as Agentic Coding Memory Behind MCP

This repository is a working exploration of Datalevin as a backend for coding-agent memory exposed through an MCP server. The prototype focuses on a thin, honest vertical slice:

- structured EAV/Datalog memory with provenance
- MCP tools/resources that operate on high-value coding memory actions
- ingestion + normalization from realistic coding artifacts
- reproducible evaluation scenarios and report generation

The goal is to answer one question with evidence: should Datalevin be used for coding-agent memory behind MCP?

Current recommendation from the prototype and evaluation harness: **fit with caveats**.

## Why Datalevin

Datalevin gives:

- ACID local transactions on LMDB
- Datalog queries for relationship-heavy retrieval
- full-text search over normalized memory bodies
- flexible EAV schema evolution for a prototype phase

That combination is promising for coding memory where facts and relationships matter more than document blobs.

## Repository Layout

- `src/datalevin_mcp/memory` schema, transaction logic, query logic
- `src/datalevin_mcp/mcp/server.clj` MCP stdio server (tools + resources)
- `src/datalevin_mcp/ingest.clj` ingestion and normalization pipeline
- `src/datalevin_mcp/eval.clj` evaluation harness + MCP smoke test
- `src/datalevin_mcp/inspect.clj` inspection CLI for debugging memory
- `examples/seed-data.edn` synthetic but realistic linked coding-memory dataset
- `eval/report.md` generated evaluation report
- `docs/architecture.md` architecture details
- `docs/next-steps.md` production hardening and alternatives

## Local Setup

### Prerequisites

- Clojure CLI
- Java 21+
- On macOS x86_64 only: `libomp` (for Datalevin native dependency)
  - installed via MacPorts in this environment:
    - `sudo port install libomp`

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
```

## MCP Surface

Implemented tools:

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

- native dependency friction on macOS x86_64 (Datalevin version and runtime library handling)
- integration glue is still required between MCP payloads and normalized entities
- full-text alone underperforms graph traversal for relationship-heavy coding questions

## Evaluation Output

Run `./bin/run-eval` to regenerate:

- `eval/report.md` with retrieval metrics by scenario
- MCP smoke test evidence (tools/resources reachable via stdio protocol)
- recommendation and rationale

The latest run reports:

- graph/EAV traversal avg recall: `0.75`
- hybrid text + graph avg recall: `0.53`
- recommendation: **fit with caveats**
