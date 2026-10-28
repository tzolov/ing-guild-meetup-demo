package com.example.demo;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The mortgage service the bank already has, with two methods exposed as tools. The customer id
 * comes from the tool context, never from the model.
 */
@Service
public class MortgageService {

	private final JdbcClient jdbcClient;

	public MortgageService(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	@Tool(description = "Returns the customer's mortgage payments for one month: due date, amount in EUR, status "
			+ "(PAID, FAILED, RETRY_PLANNED, SCHEDULED) and, for a failed payment, the reason.")
	public String mortgagePaymentStatus(@ToolParam(description = "The month, as YYYY-MM") String month,
			ToolContext toolContext) {
		int customerId = SupportDependencies.from(toolContext).customerId();
		List<Map<String, Object>> rows = this.jdbcClient
			.sql("SELECT due_date, amount, status, note FROM mortgage_payments "
					+ "WHERE customer_id = :id AND pay_month = :month ORDER BY due_date")
			.param("id", customerId)
			.param("month", month)
			.query()
			.listOfRows();
		if (rows.isEmpty()) {
			return "No mortgage payment is registered for " + month + ".";
		}
		return rows.stream().map(MortgageService::describe).collect(Collectors.joining("\n"));
	}

	@Tool(description = "Returns the customer's upcoming (scheduled or planned) mortgage payments.")
	public String upcomingMortgagePayments(ToolContext toolContext) {
		int customerId = SupportDependencies.from(toolContext).customerId();
		List<Map<String, Object>> rows = this.jdbcClient
			.sql("SELECT due_date, amount, status, note FROM mortgage_payments "
					+ "WHERE customer_id = :id AND status IN ('SCHEDULED', 'RETRY_PLANNED') ORDER BY due_date")
			.param("id", customerId)
			.query()
			.listOfRows();
		return rows.isEmpty() ? "No upcoming mortgage payments."
				: rows.stream().map(MortgageService::describe).collect(Collectors.joining("\n"));
	}

	private static String describe(Map<String, Object> row) {
		String note = row.get("NOTE") != null ? " (" + row.get("NOTE") + ")" : "";
		return "%s: EUR %s, %s%s".formatted(row.get("DUE_DATE"), row.get("AMOUNT"), row.get("STATUS"), note);
	}

}
