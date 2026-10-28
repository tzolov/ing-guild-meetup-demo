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
 * ING meetup demo 2, the version in the talk: the support agent over the bank's own services.
 *
 * One {@link ChatClient}. The model decides which of the bank's services to call: they are the
 * existing {@link AccountService} and {@link MortgageService} beans, with {@code @Tool} on the
 * methods it may use. The framework decides what the model may see: the customer id travels in the
 * tool context, never in the prompt, and {@link PiiMaskingAdvisor} replaces account identifiers
 * before the message leaves the bank. {@link SessionMemoryAdvisor} keeps the conversation, so the
 * second turn can be "And next month?".
 */
@SpringBootApplication
public class SupportAgentApplication {

	private static final String INSTRUCTIONS = """
			You are a customer support agent at a bank. The customer's name is '{customerName}'; \
			use it. Today is {today}. Amounts are in EUR.

			Answer only from the facts the tools return; never guess amounts, dates or reasons. \
			When a payment failed, say why and what happens next. Keep replies to three sentences.""";

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

			ChatClient agent = chatClientBuilder
				.defaultSystem(INSTRUCTIONS)
				.defaultTools(accounts, mortgages)                                        // the bank's beans
				.defaultToolContext(Map.of(SupportDependencies.TOOL_CONTEXT_KEY, deps))   // who the customer is
				.defaultAdvisors(
					new PiiMaskingAdvisor(),                                              // before anything leaves
					SessionMemoryAdvisor.builder(sessionService).build(),                 // the conversation
					MyLoggingAdvisor.builder().showSystemMessage(false).showAvailableTools(true).build())
				.build();

			var sessionId = "support-session-customer-" + customerId;

			for (String message : List.of(
					"My mortgage payment from NL91ABNA0417164300 did not go out this month, what happened?",
					"And next month?")) {

				System.out.println("\nCUSTOMER: " + message);

				String answer = agent.prompt()
					.system(s -> s.param("customerName", customerName).param("today", LocalDate.now()))
					.user(message)
					.advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sessionId))
					.call()
					.content();

				System.out.println("AGENT: " + answer);
			}
		}; // @formatter:on
	}

}
