package com.example.demo;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * ING meetup demo 2: the first-time-right support agent.
 *
 * Each customer message goes through two steps. A classifier call turns it into a typed
 * {@link Intent} (what the IVR decision tree used to do). The agent then answers from the bank's
 * own services, exposed as tools on the existing {@link AccountService} and {@link MortgageService}
 * beans, remembers the conversation across turns, masks account identifiers before anything
 * reaches the model, and hands off to a human colleague, with a summary, when the question is
 * outside its scope.
 */
@SpringBootApplication
public class SupportAgentApplication {

	private static final String CLASSIFY = """
			You classify messages that customers send to a bank's support desk. \
			Pick the single intent that fits best and summarise the request in one sentence. \
			A short follow-up continues the previous request, given as context.""";

	private static final String INSTRUCTIONS = """
			You are a customer support agent at a bank. The customer's name is '{customerName}'; \
			use it. Today is {today}. Amounts are in EUR.

			Answer only from the facts the tools return; never guess amounts, dates or reasons. \
			When a payment failed, say why and what happens next. Keep replies to three sentences.

			You may answer questions about the account balance and mortgage payments. Anything else, \
			in particular any request for financial advice (whether to refinance, what to invest in, \
			which product to choose), is a handoff: set handoff to true, tell the customer in one \
			sentence that a colleague will contact them within one business day, and write the \
			handoffSummary for that colleague with what the customer asked and what you already \
			know from this conversation.

			The message was classified by the intent step as: {intent}.""";

	public static void main(String[] args) {
		SpringApplication.run(SupportAgentApplication.class, args);
	}

	@Bean
	SessionService sessionService() {
		// In-memory for the demo. Production: the JDBC session repository, so the history survives a restart.
		return DefaultSessionService.builder().sessionRepository(InMemorySessionRepository.builder().build()).build();
	}

	@Bean
	public CommandLineRunner cli(ChatClient.Builder chatClientBuilder, SessionService sessionService,
			AccountService accounts, MortgageService mortgages, @Value("${demo.customer-id}") int customerId) {

		return args -> { // @formatter:off

			// The authenticated customer, as the channel (app, IVR) would hand it over
			var deps = new SupportDependencies(customerId);
			String customerName = accounts.customerName(customerId);

			// Step 1: the intent classifier, one model call that returns a typed value.
			// Stateless, so the previous turn's summary goes in as context; masked like the agent's calls.
			ChatClient classifier = chatClientBuilder.clone()
				.defaultSystem(CLASSIFY)
				.defaultAdvisors(new PiiMaskingAdvisor())
				.build();

			// Step 2: the agent over the bank's own services
			ChatClient agent = chatClientBuilder.clone()
				.defaultSystem(INSTRUCTIONS)
				.defaultTools(accounts, mortgages)
				.defaultToolContext(Map.of(SupportDependencies.TOOL_CONTEXT_KEY, deps))
				.defaultAdvisors(
					new PiiMaskingAdvisor(),
					SessionMemoryAdvisor.builder(sessionService).build(),
					MyLoggingAdvisor.builder().showAvailableTools(true).build())
				.build();

			var sessionId = "support-session-customer-" + customerId;
			String previousRequest = "none";

			for (String message : List.of(
					"My mortgage payment did not go out this month, what happened?",
					"And next month?",
					"Should I switch to a fixed rate now? My account is NL91ABNA0417164300.")) {

				System.out.println("\nCUSTOMER: " + message);

				Intent.Classification classification = classifier.prompt()
					.user("Previous request: " + previousRequest + "\nMessage: " + message)
					.call()
					.entity(Intent.Classification.class);
				previousRequest = classification.summary();

				System.out.println("INTENT: " + classification);

				SupportReply reply = agent.prompt()
					.system(s -> s.param("customerName", customerName)
						.param("today", LocalDate.now())
						.param("intent", classification.intent()))
					.user(message)
					.advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sessionId))
					.call()
					.entity(SupportReply.class);

				System.out.println("AGENT: " + reply.answer());
				if (reply.handoff()) {
					System.out.println("HANDOFF to a human colleague: " + reply.handoffSummary());
				}
			}
		}; // @formatter:on
	}

}
