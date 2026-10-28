package com.example.demo;

import java.util.regex.Pattern;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.core.Ordered;

/**
 * Masks account identifiers in the customer's message before it leaves the bank: the model, the
 * conversation memory and the traces only ever see the placeholder. Runs before the memory
 * advisor, so the stored history is masked too.
 *
 * A production version would call the bank's PII detection service; the regular expressions are
 * enough to make the point on stage.
 */
public class PiiMaskingAdvisor implements BaseAdvisor {

	private static final Pattern IBAN = Pattern.compile("\\b[A-Z]{2}\\d{2}(?:\\s?[A-Z0-9]){11,30}\\b");

	private static final Pattern LONG_NUMBER = Pattern.compile("\\b\\d{9,}\\b");

	private final int order;

	public PiiMaskingAdvisor() {
		// Before SessionMemoryAdvisor (HIGHEST_PRECEDENCE + 1000), so the memory stores the masked text
		this(Ordered.HIGHEST_PRECEDENCE + 500);
	}

	public PiiMaskingAdvisor(int order) {
		this.order = order;
	}

	@Override
	public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
		String text = request.prompt().getUserMessage().getText();
		String masked = mask(text);
		if (masked.equals(text)) {
			return request;
		}
		System.out.println("[PII] masked before the model: " + masked);
		return request.mutate().prompt(request.prompt().augmentUserMessage(masked)).build();
	}

	@Override
	public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
		return response;
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	static String mask(String text) {
		String result = IBAN.matcher(text).replaceAll("[IBAN]");
		return LONG_NUMBER.matcher(result).replaceAll("[ACCOUNT-NUMBER]");
	}

}
