# Datalevin MCP Evaluation

Generated on 2026-03-06T17:15:19.566Z.

Recommendation: **fit with caveats**

## Retrieval Summary

| Mode | Avg Recall | Avg Precision |
| --- | ---: | ---: |
| Exact entity lookup | 0.00 | 0.00 |
| Graph/EAV traversal | 0.75 | 0.59 |
| Full-text search | 0.15 | 0.13 |
| Hybrid text + graph | 0.53 | 0.40 |

## Scenario Results

### auth-recent-changes

What changed in authentication recently, and why?

Expected entities: `decision:check-session-generation-on-refresh`, `decision:inject-clock-into-refresh-tests`, `note:backfill-session-generation`, `note:follow-up-audit-token-revocation`, `patch:refresh-session-generation-check`, `patch:stabilize-refresh-rotation-test`

| Mode | Recall | Precision | Hits |
| --- | ---: | ---: | --- |
| Exact entity lookup | 0.00 | 0.00 | `` |
| Graph/EAV traversal | 1.00 | 0.67 | `decision:check-session-generation-on-refresh`, `decision:inject-clock-into-refresh-tests`, `note:backfill-session-generation`, `note:follow-up-audit-token-revocation`, `patch:refresh-session-generation-check`, `patch:stabilize-refresh-rotation-test` |
| Full-text search | 0.00 | 0.00 | `` |
| Hybrid text + graph | 0.83 | 0.63 | `decision:inject-clock-into-refresh-tests`, `note:backfill-session-generation`, `note:follow-up-audit-token-revocation`, `patch:refresh-session-generation-check`, `patch:stabilize-refresh-rotation-test` |

### failures-related-symbol

Which failures are related to this symbol?

Expected entities: `decision:check-session-generation-on-refresh`, `error:refresh-rotation-flake`, `error:stale-session-after-reset`, `patch:refresh-session-generation-check`, `patch:stabilize-refresh-rotation-test`

| Mode | Recall | Precision | Hits |
| --- | ---: | ---: | --- |
| Exact entity lookup | 0.00 | 0.00 | `` |
| Graph/EAV traversal | 0.00 | 0.00 | `` |
| Full-text search | 0.60 | 0.38 | `error:refresh-rotation-flake`, `error:stale-session-after-reset`, `patch:stabilize-refresh-rotation-test` |
| Hybrid text + graph | 1.00 | 0.63 | `decision:check-session-generation-on-refresh`, `error:refresh-rotation-flake`, `error:stale-session-after-reset`, `patch:refresh-session-generation-check`, `patch:stabilize-refresh-rotation-test` |

### prior-decisions-for-task

What prior decisions touched files that this task is about to modify?

Expected entities: `decision:check-session-generation-on-refresh`

| Mode | Recall | Precision | Hits |
| --- | ---: | ---: | --- |
| Exact entity lookup | 0.00 | 0.00 | `` |
| Graph/EAV traversal | 1.00 | 1.00 | `decision:check-session-generation-on-refresh` |
| Full-text search | 0.00 | 0.00 | `` |
| Hybrid text + graph | 0.00 | 0.00 | `` |

### timeline-failure-to-patch-to-note

Show the timeline from failing test to patch to follow-up note.

Expected entities: `decision:check-session-generation-on-refresh`, `error:stale-session-after-reset`, `note:backfill-session-generation`, `note:follow-up-audit-token-revocation`, `observation:refresh-path-skips-generation`, `patch:refresh-session-generation-check`, `tool-run:password-reset-regression`

| Mode | Recall | Precision | Hits |
| --- | ---: | ---: | --- |
| Exact entity lookup | 0.00 | 0.00 | `` |
| Graph/EAV traversal | 1.00 | 0.64 | `decision:check-session-generation-on-refresh`, `error:stale-session-after-reset`, `note:backfill-session-generation`, `note:follow-up-audit-token-revocation`, `observation:refresh-path-skips-generation`, `patch:refresh-session-generation-check`, `tool-run:password-reset-regression` |
| Full-text search | 0.00 | 0.00 | `` |
| Hybrid text + graph | 0.43 | 0.38 | `note:backfill-session-generation`, `note:follow-up-audit-token-revocation`, `patch:refresh-session-generation-check` |

### context-before-edit-module

What context should an agent load before editing this module?

Expected entities: `decision:check-session-generation-on-refresh`, `error:stale-session-after-reset`, `note:backfill-session-generation`, `patch:refresh-session-generation-check`, `symbol:src/auth/session.clj#invalidate-user-sessions`, `symbol:src/auth/session.clj#refresh-session`, `task:AUTH-142`, `task:AUTH-160`

| Mode | Recall | Precision | Hits |
| --- | ---: | ---: | --- |
| Exact entity lookup | 0.00 | 0.00 | `` |
| Graph/EAV traversal | 0.75 | 0.67 | `decision:check-session-generation-on-refresh`, `note:backfill-session-generation`, `patch:refresh-session-generation-check`, `symbol:src/auth/session.clj#invalidate-user-sessions`, `task:AUTH-142`, `task:AUTH-160` |
| Full-text search | 0.13 | 0.25 | `note:backfill-session-generation` |
| Hybrid text + graph | 0.38 | 0.38 | `note:backfill-session-generation`, `patch:refresh-session-generation-check`, `task:AUTH-160` |

## MCP Smoke Test

- Protocol version: `2025-03-26`
- Tools exposed: `15`
- Timeline entries returned by `get_task_timeline`: `11`
- Resource read URI: `memory://project/project:phoenix-auth/summary`
- Database path: `.data/memory`

## Findings

- Datalevin is strongest when the agent already has an anchor entity such as a file, symbol, task, or failing tool run. The graph joins then recover decisions, patches, and follow-up notes that keyword lookup alone misses.
- Structured provenance feels natural for tasks, tool runs, errors, observations, decisions, patches, notes, and timeline events. Long freeform tool output still wants a text field, so the model ends up hybrid rather than purely relational.
- Full-text search is useful for note recall and broad prompts, but graph traversal and task-specific joins are what make the memory system noticeably better than plain text retrieval.
- Integration pain is real on this machine: Datalevin 0.10.7 does not provide a `macosx-x86_64` native artifact, so this prototype is pinned to 0.9.27 and requires an extracted native library plus `libomp`.
- Operationally, local-first writes and repeated reads are fine for a single-user prototype, but the native dependency story and LMDB sandbox sensitivity are caveats that would matter for broader distribution.

## What Worked

- `prior-decisions-for-task` is the clearest win for Datalog joins. It answers a coding-agent question with a small, direct query over task touched files and historical decision related files.
- `get_task_timeline` and `get_symbol_memory` produce context bundles that are easy for an agent to consume because the provenance chain remains explicit.
- The MCP surface stays compact while still exposing useful higher-level operations rather than raw database primitives.

## What Hurt

- Native library handling on x86 macOS is brittle enough that setup friction is a meaningful part of the recommendation.
- Schema evolution is easy at the code level but still requires discipline: once you decide which relationship gets a dedicated attribute versus living only in a generic link entity, changing that shape ripples through ingestion and queries.
- The server is small, but there is still glue code between MCP arguments, normalized entities, and Datalevin transactions; Datalevin does not remove that application-layer mapping work.

## Recommendation Rationale

Use Datalevin for coding-agent memory **if** the expected workflow is local-first, entity-centric, and heavily anchored on files, symbols, tasks, and execution artifacts. Do not choose it expecting zero-friction distribution across developer machines; the native dependency story means this is better described as **fit with caveats** than a clean default.
