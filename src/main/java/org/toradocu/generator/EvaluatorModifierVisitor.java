package org.toradocu.generator;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BinaryExpr.Operator;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.visitor.ModifierVisitor;

import static org.toradocu.Toradocu.configuration;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.toradocu.conf.Configuration;
import org.toradocu.extractor.DocumentedExecutable;
import org.toradocu.extractor.DocumentedParameter;
import org.toradocu.util.Checks;

/**
 * Visitor that modifies the evaluator template (see method {@code visit}) to
 * generate an evaluator for a {@code ExecutableMember}.
 */
public class EvaluatorModifierVisitor extends ModifierVisitor<Object> {

	public static final String RECEIVEROBJECT_FIELD = "___INTERNAL__receiverObjectID__";
	public static final String ARGS_FIELD = "___INTERNAL__args__";
	public static final String RETVAL_FIELD = "___INTERNAL__retVal_";

	/** {@code Logger} for this class. */
	private static final Logger log = LoggerFactory.getLogger(EvaluatorModifierVisitor.class);

	public static class InstrumentationData {
		private final DocumentedExecutable method;
		private final String[] preconds;
		private final String[] excludingGuards;
		private final String[] postconds;
		private final boolean isThrows;
		private final boolean lookForPostCondViolation;

		InstrumentationData(DocumentedExecutable method, String[] preconds, String[] excludingGuards,
				String[] postconds, boolean isThrows, boolean lookForPostCondViolation) {
			Checks.nonNullParameter(method, "method");
			Checks.nonNullParameter(preconds, "preconds");
			Checks.nonNullParameter(postconds, "postconds");

			this.method = method;
			this.preconds = preconds;
			this.excludingGuards = excludingGuards;
			this.postconds = postconds;
			this.isThrows = isThrows;
			this.lookForPostCondViolation = lookForPostCondViolation;
		}
	}

	/**
	 * Modifies the methods {@code populateCalculators} of the evaluator template,
	 * injecting the appropriate source code to get an evaluator for the method.
	 *
	 * @param methodDeclaration the method declaration of the method to visit
	 * @param method            the {@code ExecutableMember} for which to generate
	 *                          the evaluator
	 * @return the {@code methodDeclaration} modified as and when needed
	 * @throws NullPointerException if {@code methodDeclaration} or
	 *                              {@code executableMember} is null
	 */
	@Override
	public Node visit(MethodDeclaration methodDeclaration, Object data) {
		Checks.nonNullParameter(methodDeclaration, "methodDeclaration");
		Checks.nonNullParameter(data, "data");
		if (!(data instanceof InstrumentationData)) {
			throw new ClassCastException("expecting data of type InstrumentationData");
		}
		InstrumentationData instrumentationData = (InstrumentationData) data;
		try {
			String methodName = methodDeclaration.getName().asString();
			if (methodName.equals("populateCalculators_preconds")) {
				addConditionDistanceCalculators(methodDeclaration, instrumentationData.method,
						instrumentationData.preconds, false, false);
				addConditionDistanceCalculators(methodDeclaration, instrumentationData.method,
						instrumentationData.excludingGuards, true, false);
			} else if (methodName.equals("populateCalculators_postconds") && instrumentationData.postconds.length > 0) {
				if (instrumentationData.isThrows) {
					addExceptionDistanceCalculator(methodDeclaration, instrumentationData.method,
							instrumentationData.postconds[0], instrumentationData.lookForPostCondViolation);
				} else {
					addConditionDistanceCalculators(methodDeclaration, instrumentationData.method,
							instrumentationData.postconds, instrumentationData.lookForPostCondViolation,
							instrumentationData.lookForPostCondViolation);
				}
			} else if (methodName.equals("test0") || methodName.equals("test1")) {
				// Set the correct input parameters for the newly created evaluator
				if (!Modifier.isStatic(instrumentationData.method.getExecutable().getModifiers())) {
					String type = "java.lang.Object";
					String name = "___receiver__object___";
					methodDeclaration.addParameter(type, name);
					Statement stmt = StaticJavaParser.parseStatement(RECEIVEROBJECT_FIELD + " = " + name + ";");
					methodDeclaration.getBody().ifPresent(blockStmt -> blockStmt.addStatement(0, stmt));
				}

				String allNames = "";
				int numArgs = instrumentationData.method.getParameters().size();
				for (int i = 0; i < numArgs; ++i) {
					String type = "java.lang.Object";
					String name = "___arg" + i + "__object___";
					methodDeclaration.addParameter(type, name);
					allNames += (allNames.isEmpty() ? "" : ", ") + name;
				}
				Statement stmt = StaticJavaParser.parseStatement(ARGS_FIELD + " = new Object[] {" + allNames + "};");
				methodDeclaration.getBody().ifPresent(blockStmt -> blockStmt.addStatement(0, stmt));
			} else if (methodName.equals("toString")) {
				Statement stmt = StaticJavaParser.parseStatement("s += \""
						+ (instrumentationData.preconds.length > 0 ? escapedQuotes(instrumentationData.preconds[0])
								: "true")
						+ " ---> "
						+ (instrumentationData.postconds.length > 0 ? escapedQuotes(instrumentationData.postconds[0])
								: "true")
						+ "\";");
				methodDeclaration.getBody().ifPresent(blockStmt -> blockStmt.addStatement(1, stmt));
			}
			return methodDeclaration;
		} catch (Exception e) {
			log.error("* Method " + configuration.getTargetClass() + "." + instrumentationData.method.getSignature()
					+ ": Issue while compiling spec into corresponding evaluator: " + "PRECONDS = "
					+ Arrays.toString(instrumentationData.preconds) + " --- " + "POSTCONDS = "
					+ Arrays.toString(instrumentationData.postconds));
			return methodDeclaration;
		}
	}

	private String escapedQuotes(String s) {
		if (s == null)
			return "";
		return s.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private void addExceptionDistanceCalculator(MethodDeclaration methodDeclaration, DocumentedExecutable spec,
			String exceptionCanonicalName, boolean lookForFailure) {
		String typeOfCalculator = lookForFailure ? "NegExceptionDistanceCalculator" : "ExceptionDistanceCalculator";
		String code = "algo.calculators.add(new " + typeOfCalculator + "() {\n"
				+ "    String exceptionCanonicalName()  {\n" + "        return \"" + exceptionCanonicalName + "\";\n"
				+ "    }\n" + "});\n";
		methodDeclaration.getBody()
				.ifPresent(blockStmt -> blockStmt.addStatement(1, StaticJavaParser.parseStatement(code)));
	}

	private void addConditionDistanceCalculators(MethodDeclaration methodDeclaration,
			DocumentedExecutable documentedExecutable, String[] conds, boolean aimToNotSatisfiedConds,
			boolean evaluateByDisjunction) {
		if (evaluateByDisjunction) {
			String code = "DistanceAlgo algo = computeDistanceWithDisjunctiveAlgo();";
			methodDeclaration.getBody().ifPresent(
					blockStmt -> blockStmt.replace(blockStmt.getStatement(0), StaticJavaParser.parseStatement(code)));
		}
		for (int i = 0; i < conds.length; ++i) {
			String cond = addCasting(conds[i], documentedExecutable);
			Expression condExpr = StaticJavaParser.parseExpression(cond);
			Expression condDistance = turnConditionToConditionDistance(condExpr.clone(), false);
			String formattedCond = condExpr.toString().replace("receiverObjectID", RECEIVEROBJECT_FIELD)
					.replace("args", ARGS_FIELD).replace("methodResultID", RETVAL_FIELD);
			String formattedCondDistance = condDistance.toString().replace("receiverObjectID", RECEIVEROBJECT_FIELD)
					.replace("args", ARGS_FIELD).replace("methodResultID", RETVAL_FIELD);
			String typeOfCalculator = aimToNotSatisfiedConds ? "NegConditionDistanceCalculator"
					: "ConditionDistanceCalculator";
			String code = "algo.calculators.add(new " + typeOfCalculator + "() {\n" + "    boolean condition()  {\n"
					+ "        return " + formattedCond + ";\n" + "    }\n" + "    double cdistance()  {\n"
					+ "        return " + formattedCondDistance + ";\n" + "    }\n" + "});\n";
			methodDeclaration.getBody()
					.ifPresent(blockStmt -> blockStmt.addStatement(1, StaticJavaParser.parseStatement(code)));
		}
	}

	private boolean isBooleanExpr(Expression expr) {
		while (expr.isEnclosedExpr()) {
			expr = expr.asEnclosedExpr().getInner();
		}
		if (expr.isBooleanLiteralExpr())
			return true;
		if (expr.isUnaryExpr() && expr.asUnaryExpr().getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT)
			return true;
		if (expr.isBinaryExpr()) {
			BinaryExpr.Operator op = expr.asBinaryExpr().getOperator();
			return op == BinaryExpr.Operator.EQUALS || op == BinaryExpr.Operator.NOT_EQUALS
					|| op == BinaryExpr.Operator.LESS || op == BinaryExpr.Operator.LESS_EQUALS
					|| op == BinaryExpr.Operator.GREATER || op == BinaryExpr.Operator.GREATER_EQUALS
					|| op == BinaryExpr.Operator.AND || op == BinaryExpr.Operator.OR;
		}
		return false;
	}

	private Expression turnConditionToConditionDistance(Expression cond, boolean negate) {
		if (cond.isEnclosedExpr()) {
			EnclosedExpr enclosed = cond.asEnclosedExpr();
			return new EnclosedExpr(turnConditionToConditionDistance(enclosed.getInner(), negate));
		} else if (cond.isBinaryExpr()) {
			BinaryExpr binaryCond = cond.asBinaryExpr();
			Expression left = binaryCond.getLeft();
			Expression right = binaryCond.getRight();
			Operator op = binaryCond.getOperator();

			// 1. Handle logical AND / OR operators
			if (op == Operator.AND || op == Operator.OR) {
				Expression turnedL = turnConditionToConditionDistance(left, negate);
				Expression turnedR = turnConditionToConditionDistance(right, negate);
				Operator turnedOp = op == Operator.AND ? (negate ? Operator.MULTIPLY : Operator.PLUS)
						: (negate ? Operator.PLUS : Operator.MULTIPLY);
				return new EnclosedExpr(new BinaryExpr(turnedL, turnedR, turnedOp));
			}

			// 2. Handle boolean literal comparisons (e.g., x == false)
			if (right.isBooleanLiteralExpr() && (op == Operator.EQUALS || op == Operator.NOT_EQUALS)) {
				boolean isFalse = !right.asBooleanLiteralExpr().getValue();
				boolean isEquals = (op == Operator.EQUALS);
				boolean newNegate = negate ? (isEquals ? !isFalse : isFalse) : (isEquals ? isFalse : !isFalse);
				return turnConditionToConditionDistance(left, newNegate);
			} else if (left.isBooleanLiteralExpr() && (op == Operator.EQUALS || op == Operator.NOT_EQUALS)) {
				boolean isFalse = !left.asBooleanLiteralExpr().getValue();
				boolean isEquals = (op == Operator.EQUALS);
				boolean newNegate = negate ? (isEquals ? !isFalse : isFalse) : (isEquals ? isFalse : !isFalse);
				return turnConditionToConditionDistance(right, newNegate);
			}

			// 3. Handle boolean-to-boolean comparison (e.g., (a == 0) == (b == 0))
			else if ((op == Operator.EQUALS || op == Operator.NOT_EQUALS)
					&& (isBooleanExpr(left) || isBooleanExpr(right))) {
				boolean isEquals = (op == Operator.EQUALS);
				boolean sameState = negate ? !isEquals : isEquals;

				Expression term1, term2;
				if (sameState) {
					// (left && right) || (!left && !right)
					term1 = new BinaryExpr(left, right, Operator.AND);
					term2 = new BinaryExpr(new UnaryExpr(left, UnaryExpr.Operator.LOGICAL_COMPLEMENT),
							new UnaryExpr(right, UnaryExpr.Operator.LOGICAL_COMPLEMENT), Operator.AND);
				} else {
					// (left && !right) || (!left && right)
					term1 = new BinaryExpr(left, new UnaryExpr(right, UnaryExpr.Operator.LOGICAL_COMPLEMENT),
							Operator.AND);
					term2 = new BinaryExpr(new UnaryExpr(left, UnaryExpr.Operator.LOGICAL_COMPLEMENT), right,
							Operator.AND);
				}
				Expression equiv = new BinaryExpr(new EnclosedExpr(term1), new EnclosedExpr(term2), Operator.OR);
				return turnConditionToConditionDistance(equiv, false);
			}

			// 4. Handle numeric comparisons
			else {
				Operator turnedOp = op;
				if (negate) {
					switch (op) {
					case EQUALS:
						turnedOp = Operator.NOT_EQUALS;
						break;
					case NOT_EQUALS:
						turnedOp = Operator.EQUALS;
						break;
					case GREATER:
						turnedOp = Operator.LESS_EQUALS;
						break;
					case GREATER_EQUALS:
						turnedOp = Operator.LESS;
						break;
					case LESS:
						turnedOp = Operator.GREATER_EQUALS;
						break;
					case LESS_EQUALS:
						turnedOp = Operator.GREATER;
						break;
					case BINARY_AND:
					case BINARY_OR:
					case XOR:
						return new DoubleLiteralExpr(1);
					default:
						throw new IllegalArgumentException(
								"Unsupported binary operator in condition distance: " + op + " in " + cond);
					}
				}

				boolean constantDistance = (turnedOp == Operator.NOT_EQUALS);
				if (!constantDistance && turnedOp == Operator.EQUALS) {
					// check if it is == for comparing references
					ArrayList<Expression> wl = new ArrayList<>();
					wl.add(left);
					wl.add(right);
					for (Expression e : wl) {
						while (e.isEnclosedExpr()) {
							e = e.asEnclosedExpr().getInner();
						}
						if (e.isNullLiteralExpr() || e.isFieldAccessExpr()
								|| (e.isCastExpr() && !e.asCastExpr().getType().isPrimitiveType())) {
							constantDistance = true;
							break;
						}
					}
				}

				if (constantDistance) {
					return new DoubleLiteralExpr(1);
				} else {
					if (turnedOp == Operator.EQUALS) {
						toString();
					}
					BinaryExpr distance = new BinaryExpr(new EnclosedExpr(left), new EnclosedExpr(right),
							Operator.MINUS);
					return distance;
				}
			}
		} else {
			return new DoubleLiteralExpr(1);
		}
	}

	/**
	 * Add the appropriate cast to each mention of a method argument or target in a
	 * given Java boolean condition.
	 *
	 * @param condition the Java boolean condition to which add the casts
	 * @param method    the method to which the {@code condition} belongs
	 * @return the input condition with casted method arguments and target
	 * @throws NullPointerException if {@code condition} or {@code method} is null
	 */
	private static String addCasting(String condition, DocumentedExecutable method) {
		Checks.nonNullParameter(condition, "condition");
		Checks.nonNullParameter(method, "method");

		java.lang.reflect.Type[] genericParamTypes = null;
		if (method.getExecutable() != null) {
			genericParamTypes = method.getExecutable().getGenericParameterTypes();
		}

		int index = 0;
		for (DocumentedParameter parameter : method.getParameters()) {
			String type;
			if (genericParamTypes != null && index < genericParamTypes.length) {
				type = formatType(genericParamTypes[index], false);
			} else {
				type = formatType(parameter.getType(), false);
			}
			condition = condition.replaceAll("args\\[" + index + "\\](?!\\d)", "((" + type + ") args[" + index + "])");
			index++;
		}

		// Casting of result object in condition.
		String returnType = formatType(method.getReturnType().getType(), false);
		if (!returnType.equals("void")) {
			condition = condition.replace(Configuration.RETURN_VALUE,
					"((" + returnType + ")" + Configuration.RETURN_VALUE + ")");
		}

		// Casting of target object in condition.
		String type = method.getDeclaringClass().getName().replace('$', '.');
		condition = condition.replace(Configuration.RECEIVER, "((" + type + ") " + Configuration.RECEIVER + ")");

		return condition;
	}

	private static String simpleTypeName(Type type) {
		if (type instanceof TypeVariable) {
			return "java.lang.Object";
		} else if (type instanceof WildcardType) {
			// TODO: shall we consider the lowerbound type? ((WildcardType)
			// type).getLowerBounds();
			return "java.lang.Object";
		} else if (type instanceof GenericArrayType) {
			return simpleTypeName(((GenericArrayType) type).getGenericComponentType()) + "[]";
		} else {
			return type.getTypeName();
		}
	}

	private static String formatType(Type type, boolean inTypeArguments) {
		return formatType(type, inTypeArguments, new java.util.HashSet<>());
	}

	/**
	 * Formats a reflect Type for casting in non-generic generated code. - Replaces
	 * type variables (e.g., T, K) and wildcards (?) with java.lang.Object (or their
	 * upper bound) to avoid wildcard capture errors during compilation. - Preserves
	 * concrete generic arguments (e.g., Map<java.lang.Object, java.lang.Integer>).
	 */
	private static String formatType(Type type, boolean inTypeArguments, java.util.Set<TypeVariable<?>> visitedTypeVars) {
		if (type == null) {
			return "java.lang.Object";
		}

		if (type instanceof Class<?>) {
			Class<?> clazz = (Class<?>) type;
			if (clazz.isArray()) {
				return formatType(clazz.getComponentType(), inTypeArguments, visitedTypeVars) + "[]";
			}
			return clazz.getName().replace('$', '.');
		} else if (type instanceof ParameterizedType) {
			ParameterizedType pType = (ParameterizedType) type;
			String raw = formatType(pType.getRawType(), inTypeArguments, visitedTypeVars);
			Type[] args = pType.getActualTypeArguments();
			if (args == null || args.length == 0) {
				return raw;
			}
			StringBuilder sb = new StringBuilder(raw).append("<");
			for (int i = 0; i < args.length; i++) {
				if (i > 0)
					sb.append(", ");
				sb.append(formatType(args[i], true, visitedTypeVars));
			}
			sb.append(">");
			return sb.toString();
		} else if (type instanceof TypeVariable<?>) {
			TypeVariable<?> typeVar = (TypeVariable<?>) type;
			if (visitedTypeVars.contains(typeVar)) {
				return "java.lang.Object";
			}
			java.util.Set<TypeVariable<?>> newVisited = new java.util.HashSet<>(visitedTypeVars);
			newVisited.add(typeVar);
			Type[] bounds = typeVar.getBounds();
			if (bounds != null && bounds.length > 0 && bounds[0] != Object.class) {
				return formatType(bounds[0], inTypeArguments, newVisited);
			}
			return "java.lang.Object";
		} else if (type instanceof WildcardType) {
			WildcardType wType = (WildcardType) type;
			Type[] upperBounds = wType.getUpperBounds();
			if (upperBounds != null && upperBounds.length > 0 && upperBounds[0] != Object.class) {
				return formatType(upperBounds[0], inTypeArguments, visitedTypeVars);
			}
			return "java.lang.Object";
		} else if (type instanceof GenericArrayType) {
			GenericArrayType gType = (GenericArrayType) type;
			return formatType(gType.getGenericComponentType(), inTypeArguments, visitedTypeVars) + "[]";
		} else {
			return type.getTypeName().replace('$', '.');
		}
	}
}
