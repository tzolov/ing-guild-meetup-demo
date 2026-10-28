package com.example.demo;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;
import org.springframework.core.task.TaskExecutor;

/**
 * ING meetup demo 1, the modular version kept simple: the compliance assistant for new
 * products as a Modular RAG pipeline with one retriever and no per-source groupings.
 *
 * A product manager pastes the description of a new mortgage product. The
 * {@link RetrievalAugmentationAdvisor} searches the description itself and four fixed
 * regulatory angles, Jev screens the passages (injection, contradiction, relevance) and
 * reranks them, and Gemini turns the best ones into a typed checklist of compliance
 * questions, each citing its excerpt.
 *
 * Compared with the naive version (ing-compliance-assistant-2): the angles find the GDPR
 * articles one search misses, and Jev drops the planted injection note before the model
 * sees it. Every stage is a building block that can be swapped.
 */
@SpringBootApplication
public class ComplianceAssistantApplication {

	private static final String INSTRUCTIONS = """
			You are a compliance analyst at a bank. A product manager describes a new financial product.

			Using only the regulation and policy excerpts provided in the context, list the compliance \
			questions the product team must answer before launch. Every question cites the excerpt it \
			rests on: copy the regulation name and the article or section from the excerpt's tag, \
			for example "EU AI Act" and "Art. 6 and Annex III". Group related obligations into one \
			question. Do not invent obligations that are not in the context. Rate the severity: \
			BLOCKER when launch is impossible without an answer, HIGH when a regulator could object, \
			MEDIUM for documentation and process points, LOW for good practice.""";

	// The four regulatory angles every product is searched from, next to the description itself.
	// Fixed, not generated: the list is the compliance team's, it is auditable, and it costs no model call.
	private static final List<String> ANGLES = List.of(
			"data protection: personal data, consent, profiling, automated decisions, transfers to third countries",
			"AI risk: high-risk classification, transparency, human oversight, right to explanation, bias",
			"third parties: outsourcing, vendor onboarding, data location, processors",
			"internal governance: product approval, model risk management, customer communication, logging and audit trail");

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
			RegulationLoader regulations, TypeSafeClient jev, TaskExecutor taskExecutor,
			@Value("${demo.product}") Resource product, @Value("${demo.top-k:8}") int topK,
			@Value("${demo.prompt-size:14}") int promptSize) {

		return args -> { // @formatter:off

			// Ingest: one Document per regulation paragraph, tagged with its source and article.
			// In production this runs once, when the regulation text changes, not per request.
			vectorStore.add(regulations.load());

			var modularRag = RetrievalAugmentationAdvisor.builder()
				// 1. Pre-retrieval: the description as it is, plus one query per regulatory angle
				.queryExpander(fixedAngles())
				// 2. Retrieval: the top passages for each of the five queries; the joiner removes duplicates
				.documentRetriever(logging(VectorStoreDocumentRetriever.builder()
					.vectorStore(vectorStore)
					.topK(topK)
					.similarityThreshold(0.3)
					.build()))
				// 3. Post-retrieval: Jev drops injected, contradicting or irrelevant passages,
				//    then reranks the rest by "could this passage answer the query?" and keeps the best
				.documentPostProcessors(
					print("[retrieved]", Document::getScore),
					JevDocumentFilter.builder(jev)
						.policy(new JevDocumentFilter.Policy(0.7, 0.7, 0.45, 0.0))
						.build(),
					print("[jev-screened]", d -> d.getMetadata().get(JevDocumentFilter.CLASSIFICATION_METADATA_KEY)),
					JevDocumentReranker.builder(jev).topK(promptSize).build(),
					print("[jev-reranked]", d -> d.getMetadata().get(JevDocumentReranker.SCORE_METADATA_KEY)))
				// 4. Generation: the excerpts and the description go into one prompt
				.queryAugmenter(ContextualQueryAugmenter.builder()
					.allowEmptyContext(true)
					.build())
				// The five retrievals run in parallel on Boot's virtual-thread executor
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
				.entity(ComplianceChecklist.class, ChatClient.EntityParamSpec::useProviderStructuredOutput);

			System.out.println(checklist.render());

		}; // @formatter:on
	}

	// The description itself (what is specific to this product) plus one standalone query per angle
	// (what a compliance review always looks at). Deterministic: no model call.
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

	// Post-processor that prints each passage with a score or label, then passes the list on unchanged
	private static DocumentPostProcessor print(String label, Function<Document, Object> score) {
		return (query, docs) -> {
			System.out.printf(GREEN + "%s %d passages" + RESET + "%n", label, docs.size());
			docs.forEach(d -> System.out.printf(GREEN + "%s %s  %s..." + RESET + "%n", label, format(score.apply(d)),
					first(d.getText(), 70)));
			return docs;
		};
	}

	// Wraps the retriever to print every query that reaches the vector store
	private static DocumentRetriever logging(DocumentRetriever delegate) {
		return query -> {
			System.out.println(GREEN + "[retrieve] " + first(query.text(), 100) + RESET);
			return delegate.retrieve(query);
		};
	}

	private static final String GREEN = "\033[38;5;82m";

	private static final String RESET = "\033[0m";

	private static String format(Object score) {
		return score instanceof Number n ? String.format("%.2f", n.doubleValue()) : String.valueOf(score);
	}

	private static String first(String text, int n) {
		return text.length() <= n ? text : text.substring(0, n).replace('\n', ' ');
	}

}
