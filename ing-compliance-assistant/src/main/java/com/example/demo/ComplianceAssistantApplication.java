package com.example.demo;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.rag.JevDocumentFilter;
import org.springaicommunity.typesafe.rag.JevDocumentReranker;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.ai.rag.preretrieval.query.expansion.QueryExpander;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;
import org.springframework.core.task.TaskExecutor;

/**
 * ING meetup demo 1, the longer version: the compliance assistant for new products as a
 * Modular RAG pipeline.
 *
 * A product manager pastes the description of a new mortgage product. The
 * {@link RetrievalAugmentationAdvisor} searches the description itself and four fixed
 * regulatory angles (no model call before retrieval: the compliance team owns the angle
 * list and retrieval is deterministic), retrieves per regulation so every source is
 * represented, screens the passages with TypeSafe's Jev model (injection, contradiction,
 * relevance), reranks them and keeps twelve, flags the ones that contradict the product
 * description as likely violations, and Gemini turns what is left into a typed checklist of
 * compliance questions, each citing its excerpt.
 *
 * Every stage is a building block that can be swapped: the retriever for pgvector, the
 * reranker for the bank's own, the angles for the compliance team's taxonomy. The short
 * version (ing-compliance-assistant-2) replaces all of it with one QuestionAnswerAdvisor.
 */
@SpringBootApplication
public class ComplianceAssistantApplication {

	private static final String INSTRUCTIONS = """
			You are a compliance analyst at a bank. A product manager describes a new financial product.

			Using only the regulation and policy excerpts provided in the context, list the compliance \
			questions the product team must answer before launch. Every question cites the excerpt it \
			rests on: copy the regulation name and the article or section from the excerpt's tag, \
			for example "EU AI Act" and "Art. 6 and Annex III". Group related obligations into one \
			question. Do not invent obligations that are not in the context. Excerpts marked as \
			conflicting with the product description point at a likely violation: put their questions \
			first and rate them at least HIGH. Rate the severity: BLOCKER when launch is impossible \
			without an answer, HIGH when a regulator could object, MEDIUM for documentation and process \
			points, LOW for good practice.""";

	// The four regulatory angles every product is searched from, next to the description itself.
	// Fixed, not generated: the list is the compliance team's, it is auditable, and retrieval no
	// longer depends on what a model happens to think of. Internal governance is on it because an
	// LLM expander never put it there.
	private static final List<String> ANGLES = List.of(
			"data protection: personal data, consent, profiling, automated decisions, transfers to third countries",
			"AI risk: high-risk classification, transparency, human oversight, right to explanation, bias",
			"third parties: outsourcing, vendor onboarding, data location, processors",
			"internal governance: product approval, model risk management, customer communication, logging and audit trail");

	// The regulations in the store. Each query is searched per source, so every regulation is
	// represented in what reaches the screening stage regardless of the query's vocabulary.
	private static final List<String> SOURCES = List.of("EU AI Act", "GDPR", "ING Product Approval Policy");

	// How many passages of each source the prompt keeps after the rerank. The bank's own product policy
	// applies to every new product in full, so every retrieved section stays; the reranker picks from the
	// large regulations. A plain top 12 by rerank score dropped policy Sections 2, 3 and 7 at 0.26 to 0.32:
	// "could this passage answer the query?" is asked against the product description, and a generic
	// obligation such as "every new product needs committee sign-off" never answers a description.
	private static final Map<String, Integer> PROMPT_QUOTA = Map.of("ING Product Approval Policy", Integer.MAX_VALUE,
			"EU AI Act", 3, "GDPR", 3);

	private static final int PROMPT_SIZE = 14;

	public static void main(String[] args) {
		SpringApplication.run(ComplianceAssistantApplication.class, args);
	}

	@Bean
	VectorStore vectorStore(EmbeddingModel embeddingModel) {
		// In-memory for the demo. Production: swap for pgvector or AlloyDB and keep the rest unchanged.
		return SimpleVectorStore.builder(embeddingModel).build();
	}

	@Bean
	public CommandLineRunner cli(ChatClient.Builder chatClientBuilder, VectorStore vectorStore,
			RegulationLoader regulations, ObjectProvider<TypeSafeClient> typeSafeClient, TaskExecutor taskExecutor,
			@Value("${demo.product}") Resource product) {

		return args -> { // @formatter:off

			// Ingest: one Document per regulation paragraph, tagged with its source and article.
			// In production this runs once, when the regulation text changes, not per request.
			vectorStore.add(regulations.load());

			var modularRag = RetrievalAugmentationAdvisor.builder()
				// 1. Pre-retrieval: the description as it is, plus one query per fixed angle. No model call,
				//    so retrieval is deterministic and costs nothing but five embeddings
				.queryExpander(fixedAngles())
				// 2. Retrieval: per query, top 4 per regulation plus top 3 unfiltered; the joiner dedups the union
				.documentRetriever(logging(perSource(vectorStore)))
				// 3. Post-retrieval: print the hits; with a Jev client, drop injected, contradicting or irrelevant
				//    passages, rerank the rest by "could this passage answer the query?", flag the conflicts
				.documentPostProcessors(postProcessors(typeSafeClient.getIfAvailable()))
				// 4. Generation: the excerpts and the description go into one prompt
				.queryAugmenter(ContextualQueryAugmenter.builder()
					.allowEmptyContext(true)
					.build())
				// Per-query retrievals run on Boot's virtual-thread executor, not the advisor's non-daemon pool
				.taskExecutor(taskExecutor)
				.build();

			ChatClient assistant = chatClientBuilder
				.defaultSystem(INSTRUCTIONS)
				.defaultAdvisors(
					modularRag,
					MyLoggingAdvisor.builder()
						.order(+1) // after the RAG advisor, so the augmented prompt is visible
						.build())
				.build();

			String productDescription = product.getContentAsString(StandardCharsets.UTF_8);

			ComplianceChecklist checklist = assistant.prompt()
				.user(productDescription)
				.call()
				// Gemini's native response schema: the JSON shape is enforced by the model API
				.entity(ComplianceChecklist.class, e -> e.useProviderStructuredOutput().validateSchema());

			System.out.println(checklist.render());

		}; // @formatter:on
	}

	// The description itself (what is specific to this product: the naive search, which finds the
	// product-shaped policy sections) plus one standalone query per angle (the passages a compliance
	// review always looks at). Deterministic: no model call. Appending the angle to the description
	// does not work: the long text dominates and all five searches return the same passages. An LLM
	// rewrite was tried first; it cost 7 to 14 seconds and dropped the product words that find the
	// policy sections.
	private static QueryExpander fixedAngles() {
		return query -> {
			var queries = new ArrayList<Query>();
			queries.add(query);
			for (String angle : ANGLES) {
				queries.add(new Query(angle, query.history(), query.context()));
			}
			return queries;
		};
	}

	// Per query: the top 4 passages of each regulation (metadata filter on the source tag) plus the
	// top 3 of the whole store, so unexpected sources such as intranet pages can still surface and
	// meet the screening stage. The joiner removes duplicates.
	private static DocumentRetriever perSource(VectorStore vectorStore) {
		return query -> {
			var docs = new ArrayList<Document>();
			for (String source : SOURCES) {
				docs.addAll(vectorStore.similaritySearch(SearchRequest.builder()
					.query(query.text())
					.topK(4)
					.similarityThreshold(0.3)
					.filterExpression("source == '" + source + "'")
					.build()));
			}
			docs.addAll(vectorStore.similaritySearch(SearchRequest.builder()
				.query(query.text())
				.topK(3)
				.similarityThreshold(0.3)
				.build()));
			return docs;
		};
	}

	private static List<DocumentPostProcessor> postProcessors(TypeSafeClient jev) {
		var stages = new ArrayList<DocumentPostProcessor>();
		stages.add(print("[retrieved]", Document::getScore));
		if (jev == null) {
			System.out
				.println(GREEN + "[jev] no TypeSafe client configured, passages go to the prompt unscreened" + RESET);
			return stages;
		}
		// The filter is the safety stage: injection 0.7, contradiction 0.7, relevance 0.45, and no
		// evidence threshold (0.0). Evidence is a quality question and the reranker answers it; at the
		// cookbook default of 0.55 the filter dropped Art. 86 (0.52), Art. 14 (0.54) and policy Section 3
		// (0.41), all real items. Passages above the contradiction threshold are kept and classified
		// CONFLICTING: in a compliance review, "contradicts the product description" is the finding.
		// The local tev1:0.8b model is not calibrated for these thresholds and may let the planted
		// intranet note through; the TypeSafe API scored it 0.99 for injection.
		stages.add(JevDocumentFilter.builder(jev)
			.policy(new JevDocumentFilter.Policy(0.7, 0.7, 0.45, 0.0))
			.build());
		stages.add(count("[jev-screened]"));
		// The quality stage: order every passage by "could this passage answer the query?", then apply
		// PROMPT_QUOTA (all policy sections, top 3 per regulation) and fill to PROMPT_SIZE by score.
		stages.add(JevDocumentReranker.builder(jev).topK(100).build());
		stages.add(print("[jev-reranked]", d -> d.getMetadata().get(JevDocumentReranker.SCORE_METADATA_KEY)));
		stages.add(perSourceQuota(PROMPT_QUOTA, PROMPT_SIZE));
		stages.add(flagConflicts());
		return stages;
	}

	// Post-processor that keeps the quota of each source (the list arrives sorted by rerank score) and
	// fills the remaining slots up to `total` by score, whatever the source.
	private static DocumentPostProcessor perSourceQuota(Map<String, Integer> quota, int total) {
		return (query, docs) -> {
			var kept = new ArrayList<Document>();
			quota.forEach((source, n) -> docs.stream()
				.filter(d -> source.equals(d.getMetadata().get("source")))
				.limit(n)
				.forEach(kept::add));
			docs.stream().filter(d -> !kept.contains(d)).limit(Math.max(0, total - kept.size())).forEach(kept::add);
			kept.sort(java.util.Comparator.comparing((Document d) -> rerankScore(d)).reversed());
			kept.forEach(d -> System.out.printf(GREEN + "[kept] %.2f  %s..." + RESET + "%n", rerankScore(d),
					first(d.getText(), 70)));
			return kept;
		};
	}

	private static double rerankScore(Document d) {
		Object score = d.getMetadata().get(JevDocumentReranker.SCORE_METADATA_KEY);
		return score instanceof Number n ? n.doubleValue() : 0.0;
	}

	private static final String GREEN = "\033[38;5;82m";

	private static final String RESET = "\033[0m";

	private static final String CONFLICT_NOTE = " [Jev: this excerpt conflicts with the product description, likely violation]";

	// Post-processor that prints each passage's score and its tag, then passes the list on unchanged
	private static DocumentPostProcessor print(String label, Function<Document, Object> score) {
		return (query, docs) -> {
			docs.forEach(d -> System.out.printf(GREEN + "%s %s  %s..." + RESET + "%n", label, score.apply(d),
					first(d.getText(), 70)));
			return docs;
		};
	}

	// Post-processor that prints how many passages survived the screening, and each one's classification
	private static DocumentPostProcessor count(String label) {
		return (query, docs) -> {
			System.out.printf(GREEN + "%s %d passages kept" + RESET + "%n", label, docs.size());
			docs.forEach(d -> System.out.printf(GREEN + "%s %s  %s..." + RESET + "%n", label,
					d.getMetadata().get(JevDocumentFilter.CLASSIFICATION_METADATA_KEY), first(d.getText(), 70)));
			return docs;
		};
	}

	// Post-processor that marks the passages Jev classified as CONFLICTING, so the prompt carries the
	// signal and the instructions can act on it. The tag at the start of the text stays as it is.
	private static DocumentPostProcessor flagConflicts() {
		return (query, docs) -> {
			var out = new ArrayList<Document>();
			for (Document d : docs) {
				boolean conflicting = JevDocumentFilter.Classification.CONFLICTING.name()
					.equals(String.valueOf(d.getMetadata().get(JevDocumentFilter.CLASSIFICATION_METADATA_KEY)));
				if (conflicting) {
					System.out.printf(GREEN + "[conflict] %s..." + RESET + "%n", first(d.getText(), 70));
					out.add(new Document(d.getId(), d.getText() + CONFLICT_NOTE, d.getMetadata()));
				}
				else {
					out.add(d);
				}
			}
			return out;
		};
	}

	// Wraps the retriever to print every rewritten and expanded query that reaches the vector store
	private static DocumentRetriever logging(DocumentRetriever delegate) {
		return query -> {
			System.out.println(GREEN + "[retrieve] " + query.text() + RESET);
			return delegate.retrieve(query);
		};
	}

	private static String first(String text, int n) {
		return text.length() <= n ? text : text.substring(0, n).replace('\n', ' ');
	}

}
