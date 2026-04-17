# Backends

## Status

Datalaga now has a pluggable backend interface with two implementations:

- `datalevin` as the current baseline backend
- `datascript-sqlite` as an experimental backend built from DataScript plus SQLite persistence and SQLite-backed full-text indexing

## Feasibility

`datascript-sqlite` is feasible for the current MCP surface because most of the important behavior already lives above the database:

- entity normalization and upsert semantics live in `memory/store.clj`
- project/task/session semantics live in MCP tool handlers
- retrieval logic is expressed as Datalog and graph traversal over normalized entities

The main missing pieces relative to Datalevin were:

- backend selection and lifecycle
- generic query and pull dispatch
- a full-text layer, because DataScript does not provide built-in full-text search

Those are now handled by the backend interface plus a SQLite FTS sidecar table for `:entity/body`.

## Tradeoffs

### Datalevin

- strongest query/search behavior out of the box
- native dependency caveats remain, especially on macOS x86_64
- keeps the original semantics for `memory_query`, `memory_pull`, and full-text search

### Datascript + SQLite

- simpler operationally: no Datalevin native wrapper path is needed when `--backend datascript-sqlite` is selected
- persistence is handled through `datascript-storage-sql`
- full-text search is approximate, not byte-for-byte identical to Datalevin
- query compatibility is high for the current codebase, but raw `memory_query` behavior should still be treated as backend-dependent

## Switching

All main entrypoints accept:

- `--backend datalevin`
- `--backend datascript-sqlite`

Examples:

```bash
./bin/datalaga --backend datascript-sqlite --db-path .data/memory.sqlite list_projects
./bin/start-mcp --backend datalevin --db-path .data/memory
```

The helper script `bin/clojure-with-backend` routes `datascript-sqlite` to plain `clojure` and keeps the Datalevin wrapper for `datalevin`.
