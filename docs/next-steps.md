# Next Steps

## Production Hardening

- Add migration/versioning discipline for schema and ingestion mappings (explicit schema version entity and migration scripts).
- Add stronger write-path validation and dead-letter handling for malformed memory events.
- Add concurrency/load tests for frequent append + read patterns expected from multi-agent sessions.
- Add backup/restore scripts for LMDB files and routine integrity checks.
- Add structured logging around MCP tool calls and Datalevin transaction failures.

## Multi-Project / Multi-Tenant Evolution

- Introduce tenant/project partition keys and enforce project scoping at query boundaries.
- Consider one-database-per-project for stronger isolation and operational simplicity.
- Add retention policies per project and archival strategy for long-running histories.
- Add project-level index management controls for full-text and optional vectors.

## If Datalevin Becomes the Wrong Fit

Potential replacement criteria:

- no native runtime friction on target developer environments
- good local-first story with robust structured querying
- acceptable write/read performance for append-heavy event streams

Possible fallback direction:

- split architecture:
  - relational or document store for canonical memory writes
  - graph/query engine for relationship-heavy reads
- keep MCP contract stable so the backend can be swapped with minimal tool-surface changes.

## Immediate Iterations Suggested

- Improve exact lookup mode so evaluation includes path/symbol token matching rather than only exact string equality.
- Add explanation traces to retrieval responses (which joins/edges produced each result).
- Add one more dataset with cross-module refactors to test whether graph recall remains strong beyond auth-focused scenarios.
