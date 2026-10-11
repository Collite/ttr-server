# ktor-configurator

> **forked-from:** the legacy predecessor platform (`shared/libs/kotlin/ktor-configurator/`), forked 2026-06-13.
> Maintained independently since the fork; do not assume parity with the original.

`KtorServerBootstrap` + `installKtorServerBase()` from the original predecessor-platform Maven artifact, extended with the `ktor-configurator/mcp` package for the MCP/Ktor base (`McpKtorConfigurator`, `McpKtorServerBootstrap`, `SafeMcpTool` for argument validation). Every kantheon Kotlin service and MCP tool uses this for its HTTP/MCP entrypoint — see `EXAMPLES.md` §1 (Ktor bootstrap) and §2 (MCP server).

## Package root

`shared.ktor` (preserved from the predecessor platform per the fork convention — see [`tasks-p1-s1.3-shared-libs.md`](../../../docs/implementation/v1/fork/tasks-p1-s1.3-shared-libs.md) pre-flight note).
