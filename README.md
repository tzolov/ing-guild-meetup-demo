# ING AI Guild meetup — the two Spring AI demos

Two Gemini demos built on the use cases ING runs on its GenAI platform, shown at the ING AI Guild meetup on
27 October 2026. Both are CLI apps: one `CommandLineRunner` and the `MyLoggingAdvisor` from `common` showing each call.
Each module has its own README with the run commands and the expected output.

The slides of the talk: [`slides/ING-2026-Oct-Final.pdf`](slides/ING-2026-Oct-Final.pdf).

| Module | ING use case | Spring AI on screen |
|---|---|---|
| [`ing-compliance-assistant-2`](ing-compliance-assistant-2/README.md) | Risk and compliance for new products: the compliance questions for a product launch, each citing its regulation. | Plain RAG (`QuestionAnswerAdvisor` over a vector store), Gemini embeddings, Gemini's native structured output into a `ComplianceChecklist` |
| [`ing-compliance-assistant-3`](ing-compliance-assistant-3/README.md) | The same use case and the same 28 passages. | Modular RAG (`RetrievalAugmentationAdvisor`: fixed regulatory angles, `VectorStoreDocumentRetriever`, Jev filter and rerank) |
| [`ing-support-agent-2`](ing-support-agent-2/README.md) | First-time-right customer support: answer from the systems of record, remember the conversation, keep the PII inside. | `@Tool` on existing `@Service` beans, the customer id in the `ToolContext`, a PII-masking advisor, `SessionMemoryAdvisor`, the audit trail in Grafana (`otel` profile) |

## Prerequisites

- Java 17+, Maven 3.6+
- Gemini through Vertex AI: `GOOGLE_CLOUD_PROJECT` and `GOOGLE_CLOUD_LOCATION` in the repo-root `.env`
  (`cp .env.example .env`; the demos load it on start-up, real environment variables win), and
  `gcloud auth application-default login`. For the Gemini Developer API instead, set
  `spring.ai.google.genai.api-key` (and `spring.ai.google.genai.embedding.api-key` for demo 1).
- Optional, demo 1: Jev, either `TYPESAFE_API_KEY` or a local `ollama pull tev1:0.8b` with the `jev-ollama` profile.
- Optional, demo 2: the Grafana stack in `docker/` for the audit trail, `cd docker && docker compose up -d`
  (Prometheus :9090, Tempo :3200/:4318, Loki :3100, Grafana :3000).

## Run

Build once from the repo root, so the modules can find `common`:

```bash
mvn install -DskipTests
```

Then:

```bash
mvn spring-boot:run -pl ing-compliance-assistant-2                                       # demo 1, the version in the talk
mvn spring-boot:run -pl ing-compliance-assistant-3                                       # demo 1, Modular RAG + Jev (TYPESAFE_API_KEY)
mvn spring-boot:run -pl ing-compliance-assistant-3 -Dspring-boot.run.profiles=jev-ollama # ... with local Jev
mvn spring-boot:run -pl ing-support-agent-2                                              # demo 2, the version in the talk
mvn spring-boot:run -pl ing-support-agent-2 -Dspring-boot.run.profiles=otel              # ... with the audit trail
```

## What the demos show

- **Demo 1** shows the prescriptive side: you decide what the model reads (retrieval) and what shape it answers
  in (the response schema). The output is an object the product workflow stores. Load, retrieve, generate.
  It runs twice: naive RAG first, then Modular RAG with Jev on the same product, to compare what changed.
- **Demo 2** shows the agentic side: the model decides which of the bank's services to call, and the framework
  decides what it may see (masking, the customer id in the tool context) and keeps the record (memory, traces).
- **Auditing** joins them: the Micrometer observations Spring AI emits become metrics, traces and logs in
  Grafana; the content switches say which spans may carry what.
- **MCP** is the next step: the same `@Service` beans the agent calls are what an MCP server would expose,
  M + N integrations instead of M x N.
