package com.example.demo;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * The intents the IVR decision tree used to route on. One model call now classifies the message
 * into a typed value the rest of the system can switch on.
 */
public enum Intent {

	ACCOUNT_BALANCE, MORTGAGE_PAYMENT, CARD_LOST_OR_STOLEN, ADDRESS_CHANGE, FINANCIAL_ADVICE, OTHER;

	public record Classification(

			@JsonPropertyDescription("The single intent that fits the customer's message best") Intent intent,

			@JsonPropertyDescription("The customer's request in one sentence, in the third person") String summary,

			@JsonPropertyDescription("Confidence in the intent, from 0.0 to 1.0") double confidence) {
	}

}
