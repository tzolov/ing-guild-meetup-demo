package com.example.demo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Loads the repo-root {@code .env} file (if present) into the Spring
 * {@link ConfigurableEnvironment} so every module in this multi-module demo
 * can rely on placeholders like {@code ${ANTHROPIC_API_KEY}} without having
 * to manually {@code source .env} first - regardless of whether the app is
 * started from an IDE run configuration, {@code mvn spring-boot:run}, or a
 * packaged jar.
 *
 * <p>Values already present as real environment variables or system
 * properties always win; this only fills in what's missing.
 */
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor {

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		Path dotenv = findDotenv();
		if (dotenv == null) {
			return;
		}

		Map<String, Object> values = parse(dotenv);
		if (!values.isEmpty()) {
			// addLast: lowest precedence, so real env vars / -D system properties
			// / command-line args set by the user still take priority.
			environment.getPropertySources().addLast(new MapPropertySource("dotenv-file", values));
		}
	}

	/**
	 * Walks up from the current working directory looking for a {@code .env}
	 * file. This covers modules living directly under the repo root (e.g.
	 * {@code 20-memory-session}) as well as nested ones (e.g.
	 * {@code 17-mcp/mcp-server}).
	 */
	private Path findDotenv() {
		Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
		for (int i = 0; i < 10 && dir != null; i++, dir = dir.getParent()) {
			Path candidate = dir.resolve(".env");
			if (Files.isRegularFile(candidate)) {
				return candidate;
			}
		}
		return null;
	}

	private Map<String, Object> parse(Path dotenv) {
		Map<String, Object> values = new LinkedHashMap<>();
		try {
			List<String> lines = Files.readAllLines(dotenv);
			for (String line : lines) {
				String trimmed = line.trim();
				if (trimmed.isEmpty() || trimmed.startsWith("#")) {
					continue;
				}
				int eq = trimmed.indexOf('=');
				if (eq <= 0) {
					continue;
				}
				String key = trimmed.substring(0, eq).trim();
				String value = trimmed.substring(eq + 1).trim();
				if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
						|| value.startsWith("'") && value.endsWith("'"))) {
					value = value.substring(1, value.length() - 1);
				}
				values.put(key, value);
			}
		}
		catch (IOException ex) {
			throw new IllegalStateException("Failed to read .env file: " + dotenv, ex);
		}
		return values;
	}

}