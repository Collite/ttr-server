# prometheus

> **forked-from:** the legacy predecessor platform (`infra/llm-gateway/`), forked 2026-06-14.
> Maintained independently since the fork; do not assume parity with the original.

**Prometheus** is the **LLM gateway** for kantheon — it centralizes and manages access to Large
Language Models (Azure OpenAI, Anthropic, …) for every other service and agent. It is the repo's
**only Spring Boot module** (a documented exception — every other JVM module is Ktor; forked as-is,
no Ktor rewrite). HTTP port **7280**, gRPC 9090. Proto: `org.tatrman.llm.v1` /
`PrometheusService` (was `ChatService`); Kotlin root `org.tatrman.prometheus.*`.

**Test policy:** mocked unit tests only live here (kotest + mockk). The integration suite
(Testcontainers / WireMock-backed upstreams / SpringBootTest) is designed and run separately
(fork Stage 2.5 T4), so those test deps and the WireMock fixtures are intentionally not in this
module. Upstream LLM API keys come from the sealed `llm-gateway-secrets` k8s Secret.

## Architecture Overview

This is a **Spring Boot** application written in **Kotlin**. It acts as a smart proxy and control plane.

### Core Stack
*   **Framework**: Spring Boot 4.0.2 (Virtual Threads Enabled)
*   **Language**: Kotlin 2.3.0
*   **Database**: PostgreSQL (Production) / H2 (Test)
*   **Messaging**: NATS (Event bus)
*   **Security**: OAuth2 Resource Server (Stateless)

### Key Features
1.  **Core Proxy**: Forwards requests to models using the Spring AI Fluent API.
2.  **Dynamic Routing**: Selects models dynamically based on HOCON rules (`rules.conf`).
3.  **Observability**: Logs all prompts and completions to PostgreSQL with Full Text Search (TSVECTOR).
4.  **Async Jobs**: Manages long-running requests via Polling and Webhooks.

## Development

### Building
```bash
just build-kt prometheus
```

### Running Locally
```bash
just run-kt prometheus
```

## Deployment
The service is deployed to Kubernetes.
```bash
just deploy-kt prometheus
```
