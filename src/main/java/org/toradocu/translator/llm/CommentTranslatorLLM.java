package org.toradocu.translator.llm;

import static java.util.stream.Collectors.toList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.toradocu.conf.Configuration;
import org.toradocu.extractor.DocumentedExecutable;
import org.toradocu.extractor.DocumentedParameter;
import org.toradocu.translator.llm.MethodsSpecification.MethodDetails;

import randoop.condition.specification.Guard;
import randoop.condition.specification.Identifiers;
import randoop.condition.specification.Operation;
import randoop.condition.specification.OperationSpecification;
import randoop.condition.specification.PostSpecification;
import randoop.condition.specification.PreSpecification;
import randoop.condition.specification.Property;
import randoop.condition.specification.ThrowsSpecification;

public class CommentTranslatorLLM {

	private static final Logger log = LoggerFactory.getLogger(CommentTranslatorLLM.class);

	public static Map<DocumentedExecutable, OperationSpecification> createSpecifications(
			List<DocumentedExecutable> members, List<MethodDetails> methodsSpecs) {
		Map<DocumentedExecutable, OperationSpecification> specs = new LinkedHashMap<>();
		Set<MethodDetails> matchedMethodSpecs = new HashSet<>();

		for (DocumentedExecutable member : members) {
			StringBuilder paramsSignature = new StringBuilder();
			for (DocumentedParameter param : member.getParameters()) {
				String paramString = param.getType().getSimpleName() + " " + param.getName();
				paramsSignature.append(paramString).append(", ");
			}
			if (paramsSignature.length() != 0) {
				paramsSignature.setLength(paramsSignature.length() - 2);
			}
			String methodSignature = member.getName() + "(" + paramsSignature.toString() + ")";

			for (MethodDetails methodDetails : methodsSpecs) {
				String jsonMethodSignature = methodDetails.getSignature();
				if (signaturesMatch(jsonMethodSignature, methodSignature)) {
					matchedMethodSpecs.add(methodDetails);
					Operation operation = Operation.getOperation(member.getExecutable());
					List<String> paramNames = member.getParameters().stream().map(DocumentedParameter::getName)
							.collect(toList());
					Map<String, String> argsMap = createArgsMap(paramNames);
					Identifiers identifiers = new Identifiers(paramNames, Configuration.RECEIVER,
							Configuration.RETURN_VALUE);
					OperationSpecification spec = new OperationSpecification(operation, identifiers);

					boolean foundSpecification = false;

					List<PreSpecification> preSpecifications = new ArrayList<>();
					for (org.toradocu.translator.llm.MethodsSpecification.PreSpecification preSpecification : methodDetails
							.getPreSpecifications()) {
						if (preSpecification.isSuccessfullyTranslated()) {
							String conditionText = replaceWithArgs(preSpecification.getConditionText(), argsMap);
							conditionText = conditionText.replace("receiverObject", "receiverObjectID");
							final Guard guard = new Guard(preSpecification.getDescription(), conditionText);
							PreSpecification presSpec = new PreSpecification(preSpecification.getDescription(), guard);
							preSpecifications.add(presSpec);
							foundSpecification = true;
						}
					}
					spec.addParamSpecifications(preSpecifications);

					List<ThrowsSpecification> throwsSpecifications = new ArrayList<>();
					for (org.toradocu.translator.llm.MethodsSpecification.ThrowSpecification throwSpecification : methodDetails
							.getThrowSpecifications()) {
						if (throwSpecification.isSuccessfullyTranslated()) {
							String conditionText = replaceWithArgs(throwSpecification.getConditionText(), argsMap);
							if (conditionText.equals("undefined")) {
								conditionText = "true";
							}
							conditionText = conditionText.replace("receiverObject", "receiverObjectID");
							final Guard guard = new Guard(throwSpecification.getDescription(), conditionText);
							ThrowsSpecification throwSpec = new ThrowsSpecification(throwSpecification.getDescription(),
									guard, throwSpecification.getExceptionType());
							throwsSpecifications.add(throwSpec);
							foundSpecification = true;
						}
					}
					spec.addThrowsSpecifications(throwsSpecifications);

					List<PostSpecification> postSpecifications = new ArrayList<>();
					for (org.toradocu.translator.llm.MethodsSpecification.PostSpecification postSpecification : methodDetails
							.getPostSpecifications()) {
						if (postSpecification.isSuccessfullyTranslated()) {
							String conditionText = replaceWithArgs(postSpecification.getConditionText(), argsMap);
							conditionText = conditionText.replace("receiverObject", "receiverObjectID");
							if (conditionText.equals("undefined")) {
								conditionText = "true";
							}
							final Guard guard = new Guard(postSpecification.getDescription(), conditionText);
							String expectedResult = replaceWithArgs(postSpecification.getExpectedResult(), argsMap);
							expectedResult = expectedResult.replace("methodResult", "methodResultID");
							expectedResult = expectedResult.replace("receiverObject", "receiverObjectID");
							Property prop = new Property(postSpecification.getDescription(), expectedResult);
							PostSpecification postSpec = new PostSpecification(postSpecification.getDescription(),
									guard, prop);
							postSpecifications.add(postSpec);
							foundSpecification = true;
						}
					}
					spec.addReturnSpecifications(postSpecifications);

					if (foundSpecification) {
						if (specs.containsKey(member)) {
							log.error("Overwriting existing specification for executable member: {}", methodSignature);
						}
						specs.put(member, spec);
					}
				}
			}
		}

		for (MethodDetails methodDetails : methodsSpecs) {
			if (!matchedMethodSpecs.contains(methodDetails)) {
				log.error("JSON specification failed to match with any class specification: {}",
						methodDetails.getSignature());
			}
		}

		return specs;
	}

	/**
	 * Matches two method signatures by comparing normalized simple method names and
	 * parameter type lists while ignoring FQNs, generics, and parameter names.
	 */
	private static boolean signaturesMatch(String jsonSig, String memberSig) {
		if (jsonSig == null || memberSig == null) {
			return false;
		}
		if (jsonSig.equals(memberSig)) {
			return true;
		}

		String normJson = normalizeSignature(jsonSig);
		String normMember = normalizeSignature(memberSig);

		if (normJson.equals(normMember)) {
			return true;
		}

		String jsonMethodName = extractMethodName(normJson);
		String memberMethodName = extractMethodName(normMember);

		if (!jsonMethodName.equals(memberMethodName)) {
			return false;
		}

		List<String> jsonParamTypes = extractParamTypes(normJson);
		List<String> memberParamTypes = extractParamTypes(normMember);

		return jsonParamTypes.equals(memberParamTypes);
	}

	private static String normalizeSignature(String signature) {
		if (signature == null) {
			return "";
		}
		String result = signature;

		// Step 1: Remove generic type parameters <...> (including nested generics)
		while (result.contains("<") && result.contains(">")) {
			result = result.replaceAll("<[^<>]*>", "");
		}

		// Step 2: Normalize varargs to array syntax
		result = result.replaceAll("\\.\\.\\.", "[]");

		// Step 3: Remove package prefixes (e.g.
		// org.apache.commons.collections4.Predicate -> Predicate)
		result = result.replaceAll("\\b[a-zA-Z_][a-zA-Z0-9_]*\\.", "");

		// Step 4: Replace standalone single-letter generic variables
		result = result.replaceAll("\\b[A-Z]\\b", "Object");

		// Step 5: Normalize spaces
		return result.replaceAll("\\s+", " ").trim();
	}

	private static String extractMethodName(String sig) {
		int parenIdx = sig.indexOf('(');
		String namePart = (parenIdx != -1) ? sig.substring(0, parenIdx).trim() : sig.trim();

		// Extract the last token before '(' to skip return type/modifiers if present
		String[] tokens = namePart.split("\\s+");
		if (tokens.length > 0) {
			namePart = tokens[tokens.length - 1];
		}

		// Strip any remaining package/enclosing class prefix
		int lastDot = namePart.lastIndexOf('.');
		if (lastDot != -1) {
			namePart = namePart.substring(lastDot + 1);
		}
		return namePart;
	}

	private static List<String> extractParamTypes(String sig) {
		List<String> types = new ArrayList<>();
		int openParen = sig.indexOf('(');
		int closeParen = sig.lastIndexOf(')');

		if (openParen == -1 || closeParen == -1 || openParen >= closeParen) {
			return types;
		}

		String paramsStr = sig.substring(openParen + 1, closeParen).trim();
		if (paramsStr.isEmpty()) {
			return types;
		}

		String[] params = paramsStr.split(",");
		for (String param : params) {
			param = param.trim();
			if (!param.isEmpty()) {
				// Strip annotations (@Annotation) and modifiers (final)
				String[] tokens = param.split("\\s+");
				List<String> cleanTokens = new ArrayList<>();
				for (String token : tokens) {
					if (!token.startsWith("@") && !token.equals("final")) {
						cleanTokens.add(token);
					}
				}
				if (!cleanTokens.isEmpty()) {
					// The first valid token is the parameter type
					types.add(cleanTokens.get(0).toLowerCase());
				}
			}
		}
		return types;
	}

	private static Map<String, String> createArgsMap(List<String> paramNames) {
		Map<String, String> map = new HashMap<>();
		int i = 0;
		for (String parName : paramNames) {
			map.put(parName, "args[" + i + "]");
			i = i + 1;
		}
		return map;
	}

	private static String replaceWithArgs(String input, Map<String, String> argsMap) {
		List<String> tokens = tokenize(input);

		List<String> processed = new ArrayList<>();
		for (int i = 0; i < tokens.size(); i++) {
			String t = tokens.get(i);
			String prev = (i > 0) ? tokens.get(i - 1) : null;

			// Replace only standalone identifiers, not member names after '.'
			if (isVariable(t) && argsMap.containsKey(t) && !".".equals(prev)) {
				processed.add(argsMap.get(t));
			} else {
				processed.add(t);
			}
		}

		String recomposed = recompose(processed);
		return recomposed;
	}

	// Regex for tokens (using non-capturing groups internally to preserve main
	// group indexing)
	private static final Pattern TOKEN_PATTERN = Pattern
			.compile("\\s*(?:(0[xX][0-9a-fA-F]+[lL]?|(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?[fFdDlL]?)|" + // Group 1: numbers (including scientific notation & hex)
					"([a-zA-Z_][a-zA-Z0-9_]*)|" + // Group 2: identifiers
					"(==|!=|>=|<=|&&|\\|\\||->|<<|>>|[()!<>])|" + // Group 3: operators and parentheses
					"(\"(?:[^\"\\\\]|\\\\.)*\")|" + // Group 4: string literals
					"('(?:[^'\\\\]|\\\\.)*')|" + // Group 5: character literals
					"(.))" // Group 6: fallback single char
			);

	private static List<String> tokenize(String input) {
		List<String> tokens = new ArrayList<>();
		Matcher m = TOKEN_PATTERN.matcher(input);

		while (m.find()) {
			if (m.group(1) != null)
				tokens.add(m.group(1)); // number
			else if (m.group(2) != null)
				tokens.add(m.group(2)); // identifier
			else if (m.group(3) != null)
				tokens.add(m.group(3)); // operator
			else if (m.group(4) != null)
				tokens.add(m.group(4)); // string literal
			else if (m.group(5) != null)
				tokens.add(m.group(5)); // character literal
			else if (m.group(6) != null)
				tokens.add(m.group(6)); // fallback
		}
		return tokens;
	}

	// Detect if a token is a variable (identifier)
	private static boolean isVariable(String token) {
		return token.matches("[a-zA-Z_][a-zA-Z0-9_]*");
	}

	// Recompose tokens into a string
	private static String recompose(List<String> tokens) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < tokens.size(); i++) {
			String t = tokens.get(i);

			// Add space between identifiers, numbers, and literals
			if (i > 0 && needsSpace(tokens.get(i - 1), t)) {
				sb.append(" ");
			}
			sb.append(t);
		}
		return sb.toString();
	}

	private static boolean needsSpace(String prev, String curr) {
		return prev.matches(".*[a-zA-Z0-9_\\]\"]$") && curr.matches("^[a-zA-Z0-9_].*");
	}

}
