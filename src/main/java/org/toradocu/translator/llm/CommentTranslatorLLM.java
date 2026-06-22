package org.toradocu.translator.llm;

import static java.util.stream.Collectors.toList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

	public static Map<DocumentedExecutable, OperationSpecification> createSpecifications(
			List<DocumentedExecutable> members, List<MethodDetails> methodsSpecs) {
		Map<DocumentedExecutable, OperationSpecification> specs = new LinkedHashMap<>();
		for (DocumentedExecutable member : members) {
			// TODO: The following way of constructing the signature is less than ideal and
			// may not work in all cases
			String paramsSignature = "";
			for (DocumentedParameter param : member.getParameters()) {
				String paramString = param.getType().getSimpleName() + " " + param.getName();
				paramsSignature = paramsSignature + paramString + ", ";
			}
			if (paramsSignature.length() != 0) {
				paramsSignature = paramsSignature.substring(0, paramsSignature.length() - 2);
			}
			String methodSignature = member.getName() + "(" + paramsSignature + ")";

			for (MethodDetails methodDetails : methodsSpecs) {
				String jsonMethodSingature = methodDetails.getSignature();
				jsonMethodSingature = eraseGenerics(jsonMethodSingature);
				if (jsonMethodSingature.equals(methodSignature)) {
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
						specs.put(member, spec);
					}
				}
			}
		}
		return specs;
	}
	
	private static String eraseGenerics(String signature) {
	    // Step 1: Remove generic declarations like <V, E>
	    String noTypeParams = signature.replaceAll("<[^>]*>", "");

	    // Step 2: Replace standalone generic type variables with Object
	    // Matches words like V, E, K, T etc., but avoids replacing actual class names
	    String replacedTypeVars = noTypeParams.replaceAll("\\b[A-Z]\\b", "Object");

	    return replacedTypeVars;
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
	
	// Regex for tokens
    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "\\s*(?:(\\d+)|" +                 // numbers
            "([a-zA-Z_][a-zA-Z0-9_]*)|" +      // identifiers
            "(==|!=|>=|<=|&&|\\|\\||[()!<>])|" + // operators and parentheses
            "(\"[^\"]*\")|" +                  // string literals
            "(.))"                             // any other single char (fallback)
    );

    private static List<String> tokenize(String input) {
        List<String> tokens = new ArrayList<>();
        Matcher m = TOKEN_PATTERN.matcher(input);

        while (m.find()) {
            if (m.group(1) != null) tokens.add(m.group(1));            // number
            else if (m.group(2) != null) tokens.add(m.group(2));       // identifier
            else if (m.group(3) != null) tokens.add(m.group(3));       // operator
            else if (m.group(4) != null) tokens.add(m.group(4));       // string literal
            else if (m.group(5) != null) tokens.add(m.group(5));       // fallback
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
        return (prev.matches("[a-zA-Z0-9_\"]+") && curr.matches("[a-zA-Z0-9_\"]+"));
    }

}
