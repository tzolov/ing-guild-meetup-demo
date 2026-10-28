package com.example.demo;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * What the agent returns on every turn. A handoff carries a summary for the human colleague, so
 * the customer does not start over.
 */
public record SupportReply(

		@JsonPropertyDescription("The reply shown to the customer") String answer,

		@JsonPropertyDescription("True when a human colleague must take over the conversation") boolean handoff,

		@JsonPropertyDescription("When handing off: what the customer asked and what is already known from this conversation, for the colleague. Otherwise empty.") String handoffSummary) {
}
