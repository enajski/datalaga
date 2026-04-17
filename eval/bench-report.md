# Backend Benchmark Report

Generated on 2026-04-17.

Method:
- Samples per scenario: `3`
- Seed dataset: `examples/seed-data.edn`
- Query: `session generation`
- Backend runs were collected per scenario, not via the one-shot `./bin/run-bench` collector. The collector still intermittently fails or hangs under the current sandbox when chaining all scenarios together, but the individual scenarios complete and produce stable numbers.

| Scenario | Datalevin mean | DataScript+SQLite mean | Datalevin median | DataScript+SQLite median | Faster backend |
| --- | ---: | ---: | ---: | ---: | --- |
| Seed dataset | 5901 ms | 1167 ms | 667 ms | 153 ms | datascript-sqlite (5.05x mean) |
| In-process open + `search_notes` | 236 ms | 80 ms | 217 ms | 69 ms | datascript-sqlite (2.96x mean) |
| CLI `list_projects` (empty db) | 25046 ms | 5665 ms | 24716 ms | 5573 ms | datascript-sqlite (4.42x mean) |
| CLI `search_notes` (seeded db) | 24636 ms | 6206 ms | 24600 ms | 6292 ms | datascript-sqlite (3.97x mean) |
| CLI `record_tool_run` (seeded db) | 16771 ms | 6197 ms | 15865 ms | 6137 ms | datascript-sqlite (2.71x mean) |
| MCP initialize | 15387 ms | 5724 ms | 15402 ms | 5780 ms | datascript-sqlite (2.69x mean) |

## Raw Samples

### Seed dataset
- Datalevin: samples `[16451 667 584]`, mean `5900.668 ms`, median `666.959 ms`, min `584.255 ms`, max `16450.792 ms`
- DataScript+SQLite: samples `[3227 153 123]`, mean `1167.477 ms`, median `153.129 ms`, min `122.640 ms`, max `3226.660 ms`

### In-process open + `search_notes`
- Datalevin: samples `[278 217 212]`, mean `235.929 ms`, median `217.399 ms`, min `211.929 ms`, max `278.461 ms`
- DataScript+SQLite: samples `[104 69 66]`, mean `79.707 ms`, median `68.774 ms`, min `66.116 ms`, max `104.230 ms`

### CLI `list_projects` (empty db)
- Datalevin: samples `[23132 27289 24716]`, mean `25045.861 ms`, median `24716.301 ms`, min `23132.287 ms`, max `27288.994 ms`
- DataScript+SQLite: samples `[5891 5573 5532]`, mean `5665.258 ms`, median `5573.105 ms`, min `5531.657 ms`, max `5891.013 ms`

### CLI `search_notes` (seeded db)
- Datalevin: samples `[25826 24600 23483]`, mean `24636.425 ms`, median `24600.333 ms`, min `23483.068 ms`, max `25825.874 ms`
- DataScript+SQLite: samples `[6379 6292 5947]`, mean `6206.167 ms`, median `6292.165 ms`, min `5946.873 ms`, max `6379.464 ms`

### CLI `record_tool_run` (seeded db)
- Datalevin: samples `[19696 15865 14752]`, mean `16771.038 ms`, median `15864.866 ms`, min `14751.880 ms`, max `19696.368 ms`
- DataScript+SQLite: samples `[6060 6393 6137]`, mean `6196.833 ms`, median `6136.853 ms`, min `6060.404 ms`, max `6393.241 ms`

### MCP initialize
- Datalevin: samples `[14446 15402 16314]`, mean `15387.448 ms`, median `15402.342 ms`, min `14445.795 ms`, max `16314.206 ms`
- DataScript+SQLite: samples `[5484 5907 5780]`, mean `5723.891 ms`, median `5780.242 ms`, min `5484.236 ms`, max `5907.197 ms`

## Takeaways

- `datascript-sqlite` was faster in every scenario measured.
- The largest practical gains are around process startup and request startup: CLI and MCP initialization improved by roughly `2.7x` to `4.4x`.
- The first sample for both backends is materially slower than the steady-state samples, but `datascript-sqlite` still keeps a clear lead on the cold path.
- These numbers support the operational simplification argument only if the current full-text compromises are acceptable. The benchmark does not change the earlier semantic caveat around search behavior.
