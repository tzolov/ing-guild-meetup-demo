# ING AI Guild meetup — the two Spring AI demos

Two Gemini demos built on the use cases ING runs on its GenAI platform, for the 35-minute Spring AI slot
on 27 October 2026. Both are CLI apps: one `CommandLineRunner`, the
`MyLoggingAdvisor` from `common`, and the Spring AI Inspector showing each call. Each module has its own README
with the run commands, the expected output and the stage notes. The deck is `ING-2026-Oct` (16 slides); the
slide-to-module mapping is below.

| Module | ING use case | Spring AI on screen |
|---|---|---|
| [`ing-compliance-assistant-2`](ing-compliance-assistant-2/README.md) | Risk and compliance for new products: the compliance questions for a product launch, each citing its regulation. **In the talk: the "Naive RAG" slide** | Plain RAG (`QuestionAnswerAdvisor` over a vector store), Gemini embeddings, Gemini's native structured output into a `ComplianceChecklist` |
| [`ing-compliance-assistant`](ing-compliance-assistant/README.md) | The same use case and the same 28 passages. **In the talk: the "Modular RAG + Jev" slide and the comparison table** | Modular RAG (`RetrievalAugmentationAdvisor`: fixed regulatory angles, per-regulation retrieval, Jev filter, rerank and conflict flags) |
| [`ing-compliance-assistant-3`](ing-compliance-assistant-3/README.md) | The same use case and passages, the modular pipeline without per-regulation retrieval or quotas. **Candidate for the "Modular RAG + Jev" slide** | Modular RAG (`RetrievalAugmentationAdvisor`: fixed regulatory angles, `VectorStoreDocumentRetriever`, Jev filter and rerank) |
| [`ing-support-agent-2`](ing-support-agent-2/README.md) | First-time-right customer support: answer from the systems of record, remember the conversation, keep the PII inside. **The version in the talk** | `@Tool` on existing `@Service` beans, the customer id in the `ToolContext`, a PII-masking advisor, `SessionMemoryAdvisor` |
| [`ing-support-agent`](ing-support-agent/README.md) | The same use case, longer version, kept for Q&A and the follow-up | Plus a typed intent classification step, a typed handoff with a summary for the colleague, the audit trail in Grafana (`otel` profile) |

## Prerequisites

- Java 17+, Maven 3.6+
- Gemini through Vertex AI: `GOOGLE_CLOUD_PROJECT` and `GOOGLE_CLOUD_LOCATION` in the repo-root `.env`
  (`cp .env.example .env`; the demos load it on start-up, real environment variables win), and
  `gcloud auth application-default login`. For the Gemini Developer API instead, set
  `spring.ai.google.genai.api-key` (and `spring.ai.google.genai.embedding.api-key` for demo 1).
- Optional: the [Spring AI Inspector](https://github.com/tzolov/voxxeddays2026-demo/tree/main/spring-ai-inspector), which lives in the voxxeddays2026-demo repo and is not published. Clone that repo,
  `cd spring-ai-inspector && mvn install -DskipTests` once, start the server with
  `mvn -pl spring-ai-inspector-server spring-boot:run`, then <http://localhost:9001>. Here, add `-Pinspector`
  to the run commands below so the demos report to it. Google GenAI calls go through the SDK, so there is no raw-wire view; the inspector
  shows the call as the advisors see it, the tool runs, the vector searches and the memory, plus the sequence view.
- Optional, demo 1: Jev, either `TYPESAFE_API_KEY` or a local `ollama pull tev1:0.8b` with the `jev-ollama` profile.
- Optional, demo 2 (longer version only): the Grafana stack in `docker/` for the audit trail,
  `cd docker && docker compose up -d` (Prometheus :9090, Tempo :3200/:4318, Loki :3100, Grafana :3000).

## Run

Build once from the repo root, so the modules can find `common`:

```bash
mvn install -DskipTests
```

Then:

```bash
mvn spring-boot:run -pl ing-compliance-assistant-2                                       # demo 1, the version in the talk
mvn spring-boot:run -pl ing-compliance-assistant                                         # demo 1, longer: Modular RAG + Jev
mvn spring-boot:run -pl ing-compliance-assistant -Dspring-boot.run.profiles=jev          # ... with Jev (TYPESAFE_API_KEY)
mvn spring-boot:run -pl ing-compliance-assistant -Dspring-boot.run.profiles=jev-ollama   # ... with local Jev
mvn spring-boot:run -pl ing-compliance-assistant-3                                       # demo 1, modular without per-source groupings (TYPESAFE_API_KEY)
mvn spring-boot:run -pl ing-compliance-assistant-3 -Dspring-boot.run.profiles=jev-ollama # ... with local Jev
mvn spring-boot:run -pl ing-support-agent-2                                              # demo 2, the version in the talk
mvn spring-boot:run -pl ing-support-agent                                                # demo 2, longer: intent step, handoff
mvn spring-boot:run -pl ing-support-agent -Dspring-boot.run.profiles=otel                # ... with the audit trail
```

## The talk, in one line each

- **Demo 1** shows the prescriptive side: you decide what the model reads (retrieval) and what shape it answers
  in (the response schema). The output is an object the product workflow stores. Load, retrieve, generate.
  It runs twice: naive RAG first, then Modular RAG with Jev on the same product, and the comparison slide
  shows what changed.
- **Demo 2** shows the agentic side: the model decides which of the bank's services to call, and the framework
  decides what it may see (masking, the customer id in the tool context) and keeps the record (memory, traces).
- **The audit slide** joins them: the Inspector for one call, Grafana for the fleet, from the same Micrometer
  observations; the content switches say which spans may carry what.
- **The MCP slide** closes: the same `@Service` beans the agent calls are what an MCP server would expose, M + N
  integrations instead of M x N. Architecture only, no demo.

## Slides to modules

| Slide | Title | What runs | Module |
|---|---|---|---|
| 2 to 6 | Why Spring AI, the overview, context curation, prescriptive vs agentic, RAG Advisor | Slides only | |
| 7 | Naive RAG - Demo | Load, retrieve, generate; the `[retrieved]` lines show the planted injection note at rank 7 and GDPR Art. 22 at rank 18 (`--demo.top-k=18` to prove it) | `ing-compliance-assistant-2` |
| 8 | Modular RAG + Jev - Advisor - Demo | The same product through the `RetrievalAugmentationAdvisor` with the `jev` profile | `ing-compliance-assistant` |
| 9 | Naive vs Modular RAG + Jev | Comparison table, numbers from the two runs above (10 vs 14 passages, 18 s vs 31 s, 20 searches, 44 Jev calls) | |
| 10 to 11 | The agent loop, Tool Calling Advisor | Two turns: tools on the `@Service` beans, the customer id in the `ToolContext` | `ing-support-agent-2` |
| 12 | Session Memory Advisor | Turn 2, "And next month?", from the same run | `ing-support-agent-2` |
| 13 | Hallucinations -> Guardrails | Slide only; the demo has the request-side `PiiMaskingAdvisor`, no response-side check | |
| 14 | Observability (Audit) | Grafana screenshot of the longer support agent (`otel` profile, `gemini-3.1-pro-preview`) | `ing-support-agent` |
| 15 | MCP AI Architecture | Slide only | |

The slide 9 numbers depend on the vector store contents and the model; rerun both compliance modules before the
talk and correct the table if the ranks or the timings moved.

## Still to do

- [ ] Record all three talk demos (`ing-compliance-assistant-2`, `ing-compliance-assistant` with the `jev` profile, `ing-support-agent-2`, export from the Inspector) as the fallback for the talk
- [ ] Rerun the two compliance modules and check the slide 9 table against the output (ranks 7 and 18, 14 passages, the timings and call counts)
- [ ] Confirm the Gemini model name ING has enabled; the talk modules run `gemini-3.8-flash`, the longer ones `gemini-3.1-pro-preview`
- [ ] Decide what to say on the guardrails slide: the demo masks the request (`PiiMaskingAdvisor`) and has no response-side check; either say so or add one
- [ ] Slide 7 labels the vector store "prod: pgvector" while the module runs `SimpleVectorStore`; keep the label as the production note or swap the store (same trade-off as before: pgvector story versus demo risk)
- [x] Replace the gpt-4o-mini Grafana screenshot on the observability slide: done, the slide shows the `gemini-3.1-pro-preview` dashboard
- [ ] Optional stretch: expose `AccountService` and `MortgageService` as an MCP server (`@McpTool`) behind slide 15
