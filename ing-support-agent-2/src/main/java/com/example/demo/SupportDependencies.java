package com.example.demo;

import org.springframework.ai.chat.model.ToolContext;

/**
 * The per-conversation context the tools read from the {@link ToolContext}: the authenticated
 * customer. The model never sees or chooses the customer id, so it cannot ask for someone else's
 * data.
 */
public record SupportDependencies(int customerId) {

	public static final String TOOL_CONTEXT_KEY = "deps";

	public static SupportDependencies from(ToolContext toolContext) {
		return (SupportDependencies) toolContext.getContext().get(TOOL_CONTEXT_KEY);
	}

}
