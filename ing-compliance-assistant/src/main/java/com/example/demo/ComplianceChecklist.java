package com.example.demo;

import java.util.Comparator;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * The typed result of a product review. The field descriptions become the JSON schema the model
 * is asked to fill, so the product workflow receives an object it can store and track, not prose.
 */
public record ComplianceChecklist(

		@JsonPropertyDescription("Name of the product under review, as given in the description") String product,

		@JsonPropertyDescription("One line on the product's overall compliance exposure") String summary,

		@JsonPropertyDescription("The compliance questions the product team must answer before launch") List<Question> questions) {

	public enum Severity {

		BLOCKER, HIGH, MEDIUM, LOW

	}

	public record Question(

			@JsonPropertyDescription("The regulation or policy the question rests on, e.g. 'EU AI Act', 'GDPR', 'ING Product Approval Policy'") String regulation,

			@JsonPropertyDescription("The article or section cited, exactly as tagged in the excerpt, e.g. 'Art. 22'") String clause,

			@JsonPropertyDescription("The question the product team must answer") String question,

			@JsonPropertyDescription("Why this applies to the product, in one sentence") String rationale,

			Severity severity) {
	}

	/** A plain-text table for the console; the web UI of a real product workflow would render the same object. */
	public String render() {
		var out = new StringBuilder();
		out.append("\nCOMPLIANCE CHECKLIST: ").append(this.product).append('\n');
		out.append(this.summary).append("\n\n");
		out.append(String.format("%-8s %-28s %-24s %s%n", "SEVERITY", "REGULATION", "CLAUSE", "QUESTION"));
		this.questions.stream()
			.sorted(Comparator.comparing(Question::severity))
			.forEach(q -> out.append(String.format("%-8s %-28s %-24s %s%n   why: %s%n", q.severity(), q.regulation(),
					q.clause(), q.question(), q.rationale())));
		return out.toString();
	}

}
