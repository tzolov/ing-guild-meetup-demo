package com.example.demo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Turns the regulation files under {@code classpath:regulations/} into one {@link Document} per
 * paragraph. Each paragraph starts with a tag such as {@code [EU AI Act, Art. 22]}; the tag stays
 * in the text, so it travels into the prompt with the excerpt and the model can cite it, and is
 * also kept as metadata for filtering.
 *
 * Stand-in for the ETL pipeline of a real deployment, where the readers would take the PDFs of
 * the regulations and the policy pages of the intranet.
 */
@Component
public class RegulationLoader {

	private static final Pattern TAG = Pattern.compile("^\\[(?<source>[^,\\]]+),\\s*(?<clause>[^\\]]+)\\]\\s*");

	public List<Document> load() throws IOException {
		var documents = new ArrayList<Document>();
		var resolver = new PathMatchingResourcePatternResolver();
		for (Resource file : resolver.getResources("classpath:regulations/*.md")) {
			String text = file.getContentAsString(StandardCharsets.UTF_8);
			for (String paragraph : text.split("\\R\\s*\\R")) {
				String trimmed = paragraph.strip();
				if (trimmed.isEmpty() || trimmed.startsWith("#")) {
					continue;
				}
				Matcher tag = TAG.matcher(trimmed);
				Map<String, Object> metadata = tag.find()
						? Map.of("source", tag.group("source"), "clause", tag.group("clause"), "file",
								String.valueOf(file.getFilename()))
						: Map.of("file", String.valueOf(file.getFilename()));
				documents.add(new Document(trimmed, metadata));
			}
		}
		return documents;
	}

}
