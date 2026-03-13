# CLI + Native Image Support — Build Summary

## What Changed

### New Files
- `src/datalaga/cli.clj` — Standalone CLI entry point (283 lines). Exposes every MCP tool as a direct command-line invocation without JSON-RPC protocol. Supports `--key value` arguments, `--json '{...}'` bulk arguments, stdin JSON (`-`), output formatting (`pretty`/`json`/`edn`), and hyphen↔underscore name normalization.
- `build.clj` — Build script for AOT-compiled uberjar. Compiles `datalaga.cli` namespace and packages as `target/datalaga.jar` with main class `datalaga.cli`.
- `bin/datalaga` — Shell wrapper to run the CLI via `clojure -M:cli`.
- `bin/build-native` — GraalVM native-image build script. Builds uberjar, then compiles to standalone binary at `target/datalaga`. Requires GraalVM 25+ with `native-image`.
- `resources/META-INF/native-image/datalaga/datalaga/native-image.properties` — GraalVM native-image configuration with Clojure AOT, direct linking, and Datalevin runtime initialization.
- `test/datalaga/cli_test.clj` — CLI unit and integration tests (156 lines, 4 test groups).

### Modified Files
- `deps.edn` — Added `:cli`, `:build`, and `:native-image` aliases with `tools.build` and `graal-build-time` dependencies.
- `src/datalaga/mcp/server.clj` — Made `call-tool!`, `tools`, and `deep-keywordize` public so CLI can reuse them.
- `test/datalaga/test_runner.clj` — Added `datalaga.cli-test` to the test suite.
- `README.md` — Added CLI usage documentation, native image build instructions, and updated repository layout.

## Architecture

The CLI reuses the MCP server's `call-tool!` function directly — no protocol duplication. Adding a new tool to the MCP server automatically makes it available as a CLI command.

```
CLI arguments → parse-tool-args → mcp/call-tool! → format-output → stdout
MCP JSON-RPC → handle-request  → mcp/call-tool! → tool-result  → JSON-RPC response
```

## Verification

- **Tests**: 14 tests, 74 assertions, 0 failures, 0 errors
- **CLI smoke tests**: `tools`, `help`, `ensure_project`, `list_projects` all verified
- **Uberjar**: Builds successfully, `java -jar target/datalaga.jar` works
- **Native image**: Configuration in place; requires GraalVM 25+ to compile (not available in CI)

## Usage

```bash
# Via Clojure CLI
./bin/datalaga tools
./bin/datalaga list_projects -f json

# Via uberjar
clj -T:build uber
java -jar target/datalaga.jar list_projects

# Via native image (requires GraalVM)
./bin/build-native
./target/datalaga list_projects
```
