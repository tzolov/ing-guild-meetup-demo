package com.example.demo;

import java.nio.charset.StandardCharsets;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;

/**
 * ING meetup demo 1, the short version: the compliance assistant for new products.
 *
 * A product manager pastes the description of a new mortgage product. The
 * {@link QuestionAnswerAdvisor} finds the regulation and policy passages closest to it in the
 * vector store and puts them in the prompt, and Gemini turns them into a typed checklist of
 * compliance questions, each citing its passage.
 *
 * Three steps, three lines: load the passages, retrieve the matching ones, generate the
 * checklist. The longer version (ing-compliance-assistant) replaces the one advisor with a
 * Modular RAG pipeline: query rewriting, expansion into several regulatory angles, and Jev
 * screening and reranking the passages before they reach the model.
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
			RegulationLoader regulations, @Value("${demo.product}") Resource product,
			@Value("${demo.top-k:10}") int topK) {

		return args -> { // @formatter:off

			// 1. Load: one Document per regulation paragraph, tagged with its source and article.
			//    In production this runs once, when the regulation text changes, not per request.
			vectorStore.add(regulations.load());

			var searchRequest = SearchRequest.builder().topK(topK).similarityThreshold(0.3).build();

			ChatClient assistant = chatClientBuilder
				.defaultSystem(INSTRUCTIONS)
				.defaultAdvisors(
					// 2. Retrieve: the passages closest to the product description go into the prompt
					QuestionAnswerAdvisor.builder(vectorStore).searchRequest(searchRequest).build(),
					MyLoggingAdvisor.builder()
						.order(+1) // after the RAG advisor, so the augmented prompt is visible
						.build())
				.build();

			String productDescription = product.getContentAsString(StandardCharsets.UTF_8);

			// 3. Generate: Gemini fills the ComplianceChecklist schema, enforced by the model API
			ComplianceChecklist checklist = assistant.prompt()
				.user(productDescription)
				.call()
				.entity(ComplianceChecklist.class, ChatClient.EntityParamSpec::useProviderStructuredOutput);

			System.out.println(checklist.render());

		}; // @formatter:on
	}

	private static final String GREEN = "\033[38;5;82m";

	private static final String RESET = "\033[0m";

	private static String first(String text, int n) {
		return text.length() <= n ? text : text.substring(0, n).replace('\n', ' ');
	}

}
