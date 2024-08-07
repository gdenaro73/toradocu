package org.toradocu.generator;

import static org.toradocu.Toradocu.configuration;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.ArrayCreationLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Modifier.Keyword;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MarkerAnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.visitor.VoidVisitor;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringEscapeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.toradocu.extractor.DocumentedExecutable;
import org.toradocu.util.Checks;
import randoop.condition.specification.OperationSpecification;
import randoop.condition.specification.PostSpecification;
import randoop.condition.specification.PreSpecification;
import randoop.condition.specification.Specification;
import randoop.condition.specification.ThrowsSpecification;

/**
 * The test case generator. The method {@code createTests} of this class creates
 * the test cases for a list of {@code ExecutableMember}.
 */
public class TestGeneratorValidation {
	public static final int MAX_EVALUATORS_PER_EVOSUITE_CALL = 10;
	public static final String VALIDATORS_FOLDER = "validation";
	public static final String EVALUATORS_FOLDER = "evaluators";
	private static HashMap<String, Integer> evosuiteBudgets = new HashMap<String, Integer>();

	/** {@code Logger} for this class. */
	private static final Logger log = LoggerFactory.getLogger(TestGeneratorValidation.class);

	/**
	 * Creates evaluators to allow EvoSuite to check fitness wrt the given
	 * {@code specs}, and then launches EvoSuite with the evaluators as fitness
	 * functions to generate the test cases. This method creates two evaluators for
	 * each property {@code spec} learned with Toradocu for each method:
	 * 
	 * - an evaluator aimed at generating a test case that shows an execution for
	 * which the {@code spec} holds - an evaluator aimed at generating a test case
	 * that shows an execution for which the {@code spec} is violated (if any)
	 * 
	 * Recall that, in the case of Postconditions, The evaluator will enforce that
	 * the test cases reach method-exits with no exception.
	 *
	 * Usually we expect that the first evaluator can be satisfied, while the second
	 * could be unsatisfiable if there are no bugs. However, there is also the risk
	 * that we end up with discovering the weakness of some property {@code spec}
	 * derived with Toradocu.
	 *
	 * NB: we generate test cases for Throws-specs and Postcondition-specs with
	 * non-empty guards. This method raises an error if the following assumption is
	 * vilated: The specs with empty guards can include either a single Throws spec
	 * with empty guard, or a set of Postcondition-spec (but no Throws-spec).
	 * 
	 * @param specifications the specifications (to be tested) learned with Toadocu.
	 *                       Must not be null.
	 */
	public static void createTests(Map<DocumentedExecutable, OperationSpecification> specifications)
			throws IOException {
		// Check for abstract class and skip generation if abstract
		String claxPathStr = configuration.getTargetClass().replace(".", "/");
		String claxToSearch = "";
		String innerClaxToSearch = "";
		if (claxPathStr.contains("$")) {
			claxToSearch = claxPathStr.substring(claxPathStr.lastIndexOf("/") + 1, claxPathStr.lastIndexOf("$"));
			innerClaxToSearch = claxPathStr.substring(claxPathStr.lastIndexOf("$") + 1);
			claxPathStr = claxPathStr.substring(0, claxPathStr.lastIndexOf("$"));
		} else {
			claxToSearch = claxPathStr.substring(claxPathStr.lastIndexOf("/") + 1);
		}
		String testedClaxDir = configuration.sourceDir.toString() + "/" + claxPathStr + ".java";
		File testedClax = new File(testedClaxDir);
		CompilationUnit cu = null;
		try {
			cu = StaticJavaParser.parse(testedClax);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
		Optional<ClassOrInterfaceDeclaration> oClax = cu.getClassByName(claxToSearch);
		if (oClax.isPresent()) {
			ClassOrInterfaceDeclaration clax = oClax.get();
			if (innerClaxToSearch.isEmpty()) {
				if (clax.isAbstract()) {
					log.warn("Abstract class found! Oracle generation skipped.");
					return;
				}
			} else {
				// Manage inner classes
				NodeList<BodyDeclaration<?>> members = clax.getMembers();
				boolean found = false;
				for (BodyDeclaration<?> member : members) {
					if (member.isClassOrInterfaceDeclaration()) {
						ClassOrInterfaceDeclaration innerClax = member.asClassOrInterfaceDeclaration();
						if (innerClax.getName().asString().equals(innerClaxToSearch))
							if (!innerClax.isAbstract())
								found = true;
						break;
					}
				}
				if (!found) {
					log.warn("Abstract inner class found! Oracle generation skipped.");
					return;
				}
			}
		} else {
			log.error("Class not found in class file! Are you sure it's not an interface?");
			return;
		}

		Checks.nonNullParameter(specifications, "specifications");

		// Output Dir
		String output = configuration.getTestOutputDir();
		if (output.contains(configuration.getTargetClass())) {
			output = output.substring(0, output.indexOf(configuration.getTargetClass()));
		}
		final Path outputDir = Paths.get(output);

		// Create output directory where test cases are saved.

		final boolean testsDirCreationSucceeded = createOutputDir(outputDir.toString(), false);
		if (!testsDirCreationSucceeded || specifications.isEmpty()) {
			log.error("Test generation failed, cannot create dir:" + outputDir);
			return;
		}

		// Step 1/2: Generate test cases for the target class by launching EvoSuite
		String targetClass = configuration.getTargetClass();
		log.info("Going to generate validation test cases for " + targetClass + " oracles");

		if (!configuration.isSkipValidationTestsGeneration()) {
			if (evosuiteBudgets.isEmpty()) {
				obtainEvosuiteBudgets();
			}
			int evosuiteBudget = 60;
			if (evosuiteBudgets.containsKey(targetClass)) {
				evosuiteBudget = evosuiteBudgets.get(targetClass);
			}

			// Launch EvoSuite
			List<String> evosuiteCommand = buildEvoSuiteCommand(outputDir, evosuiteBudget);
			final Path evosuiteLogFilePath = outputDir.resolve("evosuite-log-" + targetClass + ".txt");

			try {
				Process processEvosuite = launchProcess(evosuiteCommand, evosuiteLogFilePath);
				log.info("Launched EvoSuite process, command line: " + evosuiteCommand.stream().reduce("", (s1, s2) -> {
					return s1 + " " + s2;
				}));
				try {
					processEvosuite.waitFor();
				} catch (InterruptedException e) {
					// the performer was shut down: kill the EvoSuite job
					log.info("Unexpected InterruptedException while running EvoSuite: " + e);
					processEvosuite.destroy();
				}
			} catch (IOException e) {
				log.error("Unexpected I/O error while running EvoSuite: " + e);
				throw new RuntimeException(e);
			}
		}

		// Step 2/2: Enrich the generated test cases with assumptions and assertions
		enrichTestWithOracle(outputDir, targetClass, specifications);

	}

	private static void obtainEvosuiteBudgets() {
		File budgetFile = new File("EvosuiteBudgets.csv");
		try {
			List<String> classBudgets = Files.readAllLines(budgetFile.toPath());
			for (int i = 1; i < classBudgets.size(); i++) {
				String claxBudget = classBudgets.get(i);
				String[] claxBudgetArray = claxBudget.split(";");
				String clax = claxBudgetArray[0].replace("\"", "");
				int budget = Integer.parseInt(claxBudgetArray[1]);
				evosuiteBudgets.put(clax, budget);
			}
		} catch (IOException | NumberFormatException e) {
			log.error("Error in reading Evosuite budget file! Will use default budget: 60", e);
		}
	}

	private static void enrichTestWithOracle(Path testsDir, String targetClass,
			Map<DocumentedExecutable, OperationSpecification> allSpecs) {
		String testName = targetClass + "_ESTest";
		final Path testCaseAbsPath = testsDir.resolve(testName.replace('.', File.separatorChar) + ".java");
		File currentTestCase = new File(testCaseAbsPath.toUri());
		// manage case in which EvoSuite failed to generate this test case
		if (!currentTestCase.exists()) {
			try {
				currentTestCase.getParentFile().mkdirs();
				currentTestCase.createNewFile();
			} catch (IOException e) {
				log.error("Error while creating empty test case: " + currentTestCase, e);
			}
			// Create fake test case for reporting purposes
			String testClaxName = testName.substring(testName.lastIndexOf(".") + 1);
			String testClaxPackage = testName.substring(0, testName.lastIndexOf("."));
			CompilationUnit cunew = new CompilationUnit();
			cunew.setPackageDeclaration(testClaxPackage);
			ClassOrInterfaceDeclaration clax = cunew.addClass(testClaxName);
			MethodDeclaration method = clax.addMethod("emptyTest", Modifier.Keyword.PUBLIC);
			method.setType("void");
			method.addAnnotation("org.junit.Test");
			// write out the enriched test case
			try (FileOutputStream output = new FileOutputStream(currentTestCase)) {
				output.write(cunew.toString().getBytes());
			} catch (IOException e) {
				log.error("Error while creating empty test case: " + currentTestCase, e);
			}
		}

		if (configuration.isValidationTestsBackupGeneration()) {
			// Create backup test case
			String backupTestCaseAbsPath = currentTestCase.getAbsolutePath().replace("generated-tests",
					"generated-tests-backup");
			File backupTestCase = new File(backupTestCaseAbsPath);
			try {
				backupTestCase.mkdirs();
				Path copiedTestCase = Paths.get(backupTestCase.toURI());
				Files.copy(testCaseAbsPath, copiedTestCase, StandardCopyOption.REPLACE_EXISTING);
			} catch (Exception e) {
				log.error("Fail in backup test cases creation.", e);
			}
			String currentScaffolding = testCaseAbsPath.toString().replace("_ESTest", "_ESTest_scaffolding");
			Path scaffoldingFilePath = Paths.get(currentScaffolding);
			String backupScaffoldingAbsPath = backupTestCaseAbsPath.replace("_ESTest", "_ESTest_scaffolding");
			try {
				Path copiedScaffolding = Paths.get(backupScaffoldingAbsPath);
				Files.copy(scaffoldingFilePath, copiedScaffolding, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e) {
				log.warn("Could not copy scaffolding file. This is normal if Evosuite failed in generating a case.");
			}
		}

		CombinedTypeSolver combinedTypeSolver = new CombinedTypeSolver();
		combinedTypeSolver.add(new JavaParserTypeSolver(configuration.sourceDir));
		combinedTypeSolver.add(new ReflectionTypeSolver());
		try {
			combinedTypeSolver.add(new JarTypeSolver(configuration.getEvoSuiteJar()));
		} catch (IOException e) {
			log.error("Wrong path to Evosuite lib.", e);
		}
		JavaSymbolSolver symbolSolver = new JavaSymbolSolver(combinedTypeSolver);
		StaticJavaParser.getConfiguration().setSymbolResolver(symbolSolver);
		CompilationUnit cu = null;
		try {
			cu = StaticJavaParser.parse(currentTestCase);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
		List<ClassOrInterfaceDeclaration> claxes = cu.findAll(ClassOrInterfaceDeclaration.class);
		if (claxes.isEmpty())
			throw new RuntimeException("Unexpected test structure");
		else {
			ClassOrInterfaceDeclaration clax = claxes.get(0);
			ClassOrInterfaceType uniqueGuardIdsType = StaticJavaParser
					.parseClassOrInterfaceType("java.util.HashSet<String>");
			Expression uniqueGuardIdsIdsInit = StaticJavaParser.parseExpression("new java.util.HashSet<String>()");
			clax.addFieldWithInitializer(uniqueGuardIdsType, "uniqueGuardIds_lta", uniqueGuardIdsIdsInit,
					Keyword.PUBLIC);

			// Create and initialize violatedPreconds and failedConds
			clax.addFieldWithInitializer(PrimitiveType.intType(), "violatedPreconds",
					StaticJavaParser.parseExpression("0"), Keyword.PUBLIC, Keyword.STATIC);
			clax.addFieldWithInitializer(PrimitiveType.intType(), "failedConds", StaticJavaParser.parseExpression("0"),
					Keyword.PUBLIC, Keyword.STATIC);
			clax.addFieldWithInitializer(PrimitiveType.intType(), "satisfiedPreconds",
					StaticJavaParser.parseExpression("0"), Keyword.PUBLIC, Keyword.STATIC);
			clax.addFieldWithInitializer(PrimitiveType.intType(), "passedConds", StaticJavaParser.parseExpression("0"),
					Keyword.PUBLIC, Keyword.STATIC);
			clax.addFieldWithInitializer(PrimitiveType.intType(), "inconclusivePass",
					StaticJavaParser.parseExpression("0"), Keyword.PUBLIC, Keyword.STATIC);
			clax.addFieldWithInitializer(PrimitiveType.intType(), "inconclusiveFail",
					StaticJavaParser.parseExpression("0"), Keyword.PUBLIC, Keyword.STATIC);

			// Create and initialize fields for counting covered contracts per method
			clax.addFieldWithInitializer(PrimitiveType.intType(), "coveredContracts",
					StaticJavaParser.parseExpression("0"), Keyword.PUBLIC, Keyword.STATIC);
			ClassOrInterfaceType coveredContractsPerMethodType = StaticJavaParser
					.parseClassOrInterfaceType("java.util.ArrayList<Integer>");
			Expression coveredContractsPerMethodInit = StaticJavaParser
					.parseExpression("new java.util.ArrayList<Integer>()");
			clax.addFieldWithInitializer(coveredContractsPerMethodType, "coveredContractsPerMethod",
					coveredContractsPerMethodInit, Keyword.PUBLIC, Keyword.STATIC);

			// Add contracts method
			MethodDeclaration mdContracts = clax.addMethod("contracts", Modifier.Keyword.PUBLIC,
					Modifier.Keyword.STATIC);
			mdContracts.setType("String [][]");
			BlockStmt contractsBlock = mdContracts.createBody();
			ReturnStmt returnContractsStmt = new ReturnStmt();
			contractsBlock.addStatement(returnContractsStmt);
			ArrayCreationExpr contractsArray = new ArrayCreationExpr();
			contractsArray.setElementType("String");
			ArrayCreationLevel level = new ArrayCreationLevel();
			NodeList<ArrayCreationLevel> levels = new NodeList<ArrayCreationLevel>();
			levels.add(level);
			levels.add(level);
			contractsArray.setLevels(levels);
			ArrayInitializerExpr contractsArrayInit = new ArrayInitializerExpr();
			contractsArray.setInitializer(contractsArrayInit);
			returnContractsStmt.setExpression(contractsArray);
			NodeList<Expression> contracts = contractsArrayInit.getValues();

			// Add beforeClass method
			ClassOrInterfaceType globalGuardsIdsType = StaticJavaParser
					.parseClassOrInterfaceType("java.util.HashMap<String, String>");
			Expression globalGuardsIdsInit = StaticJavaParser
					.parseExpression("new java.util.HashMap<String, String>()");
			clax.addFieldWithInitializer(globalGuardsIdsType, "globalGuardsIds_lta", globalGuardsIdsInit,
					Keyword.PUBLIC, Keyword.STATIC);
			MethodDeclaration mdInit = clax.addMethod("init", Modifier.Keyword.PUBLIC, Modifier.Keyword.STATIC);
			mdInit.setType(new com.github.javaparser.ast.type.VoidType());
			mdInit.addAnnotation(new MarkerAnnotationExpr("org.junit.BeforeClass"));
			BlockStmt bs = mdInit.createBody();
			int identifier = 0;
			int specificationCounter = 0;
			for (DocumentedExecutable targetMethod : allSpecs.keySet()) {
				OperationSpecification os = allSpecs.get(targetMethod);
				if (os.getPostSpecifications().size() != 0 || os.getThrowsSpecifications().size() != 0) {
					List<ExpressionStmt> methodCallsToEnrich = identifyMethodCallsToEnrich(cu, targetMethod);
					addReportCatchSupportVariables(methodCallsToEnrich);
					ArrayList<Specification> targetSpecs = new ArrayList<>();
					targetSpecs.addAll(os.getThrowsSpecifications());
					targetSpecs.addAll(os.getPostSpecifications());
					HashMap<Integer, HashMap<String, IfStmt>> existingPrecondChecks = new HashMap<Integer, HashMap<String, IfStmt>>();
					for (Specification spec : targetSpecs) {
						StringLiteralExpr method = new StringLiteralExpr(targetMethod.getSignature());
						StringLiteralExpr contr = new StringLiteralExpr(StringEscapeUtils.escapeJava(spec.toString()));
						NodeList<Expression> mc = new NodeList<Expression>();
						mc.add(method);
						mc.add(contr);
						ArrayInitializerExpr methodContractInit = new ArrayInitializerExpr(mc);
						contracts.add(methodContractInit);
						if (methodCallsToEnrich.size() != 0) {
							bs.addStatement(
									"globalGuardsIds_lta.put(\"" + specificationCounter + "\",\"not_executed\");");
							identifier = enrichTestWithOracle(spec, targetMethod, existingPrecondChecks,
									methodCallsToEnrich, os.getThrowsSpecifications(), allSpecs, identifier,
									specificationCounter);
						} else {
							bs.addStatement(
									"globalGuardsIds_lta.put(\"" + specificationCounter + "\",\"not_present\");");
						}
						specificationCounter++;
					}
					processReportCatchSupportVariables(methodCallsToEnrich);
				}
			}

			// Add AfterClass
			mdInit = clax.addMethod("generateReport", Modifier.Keyword.PUBLIC, Modifier.Keyword.STATIC);
			mdInit.setType(new com.github.javaparser.ast.type.VoidType());
			mdInit.addAnnotation(new MarkerAnnotationExpr("org.junit.AfterClass"));
			BlockStmt bs2 = mdInit.createBody();
			bs2.addStatement("lta.test.utils.TestUtils.report(globalGuardsIds_lta, \"" + targetClass
					+ "\", contracts(), satisfiedPreconds, violatedPreconds, passedConds, failedConds, inconclusivePass, inconclusiveFail, coveredContractsPerMethod);");

			// Add Before
			mdInit = clax.addMethod("before", Modifier.Keyword.PUBLIC);
			mdInit.setType(new com.github.javaparser.ast.type.VoidType());
			mdInit.addAnnotation(new MarkerAnnotationExpr("org.junit.Before"));
			BlockStmt bsBefore = mdInit.createBody();
			bsBefore.addStatement("coveredContracts = 0;");

			// Add After
			mdInit = clax.addMethod("after", Modifier.Keyword.PUBLIC);
			mdInit.setType(new com.github.javaparser.ast.type.VoidType());
			mdInit.addAnnotation(new MarkerAnnotationExpr("org.junit.After"));
			BlockStmt bsAfter = mdInit.createBody();
			bsAfter.addStatement("coveredContractsPerMethod.add(coveredContracts);");

			// write out the enriched test case
			try (FileOutputStream output = new FileOutputStream(currentTestCase)) {
				output.write(cu.toString().getBytes());
			} catch (IOException e) {
				log.error("Error while writing the enriched test case to file: " + currentTestCase, e);
			}
		}
	}

	private static void addReportCatchSupportVariables(List<ExpressionStmt> methodCallsToEnrich) {
		for (ExpressionStmt methodCallToEnrich : methodCallsToEnrich) {
			// Call for which we shall add the oracle
			NodeList<Statement> methodCallBlockStmt = null;
			try {
				methodCallBlockStmt = ((BlockStmt) methodCallToEnrich.getParentNode().get()).getStatements();
			} catch (Exception e) {
				log.error(
						"Could not find parent node while trying to add support variables used for report in catch block.",
						e);
			}
			Optional<Node> n = methodCallBlockStmt.getParentNode();
			if (n.isPresent()) {
				n = n.get().getParentNode();
				if (n.isPresent() && (n.get() instanceof TryStmt)) {
					TryStmt ts = (TryStmt) n.get();
					for (CatchClause cc : ts.getCatchClauses()) {
						BlockStmt catchBody = cc.getBody();
						catchBody.addStatement(StaticJavaParser
								.parseStatement("java.util.HashSet<String> pass = new java.util.HashSet<String>();"));
						catchBody.addStatement(StaticJavaParser
								.parseStatement("java.util.HashSet<String> fail = new java.util.HashSet<String>();"));
						catchBody.addStatement(StaticJavaParser.parseStatement("Throwable raisedEx = null;"));
					}
				}
			}
		}
	}

	private static void processReportCatchSupportVariables(List<ExpressionStmt> methodCallsToEnrich) {
		for (ExpressionStmt methodCallToEnrich : methodCallsToEnrich) {
			// Call for which we shall add the oracle
			NodeList<Statement> methodCallBlockStmt = null;
			try {
				methodCallBlockStmt = ((BlockStmt) methodCallToEnrich.getParentNode().get()).getStatements();
			} catch (Exception e) {
				log.error(
						"Could not find parent node while trying to process support variables used for report in catch block.",
						e);
			}
			Optional<Node> n = methodCallBlockStmt.getParentNode();
			if (n.isPresent()) {
				n = n.get().getParentNode();
				if (n.isPresent() && (n.get() instanceof TryStmt)) {
					TryStmt ts = (TryStmt) n.get();
					for (CatchClause cc : ts.getCatchClauses()) {
						BlockStmt catchBody = cc.getBody();
						IfStmt ifstmt = new IfStmt();
						ifstmt.setCondition(StaticJavaParser.parseExpression("!pass.isEmpty()"));
						ifstmt.setThenStmt(StaticJavaParser.parseBlock(
								"{if (pass.size() == 1) {for (String p : pass) {globalGuardsIds_lta.put(p, \"pass\");passedConds++;}} else {for (String p : pass) {if(!globalGuardsIds_lta.get(p).equals(\"pass\") && !globalGuardsIds_lta.get(p).equals(\"inconclusive_fail\") ) {globalGuardsIds_lta.put(p, \"inconclusive_pass\");inconclusivePass++;}}}}"));
						ifstmt.setElseStmt(StaticJavaParser.parseBlock(
								"{if (fail.size() == 1) {for (String f : fail) {globalGuardsIds_lta.put(f, \"fail\");failedConds++;}} else {for (String f : fail) {if(!globalGuardsIds_lta.get(f).equals(\"pass\")) {globalGuardsIds_lta.put(f, \"inconclusive_fail\");inconclusiveFail++;}}}if (raisedEx !=  null) {throw raisedEx;}}"));
						catchBody.addStatement(ifstmt);
					}
				}
			}
		}
	}

	private static List<ExpressionStmt> identifyMethodCallsToEnrich(CompilationUnit cu,
			DocumentedExecutable targetMethod) {
		List<ExpressionStmt> callsToTargetMethodTest = new ArrayList<ExpressionStmt>();
		SupportStructure ss = new SupportStructure(targetMethod, callsToTargetMethodTest);
		VoidVisitor<SupportStructure> visitor = new IdentifyCallsToEnrichVisitor();
		visitor.visit(cu, ss);
		return ss.getTargetCallsList();
	}

	private static int enrichTestWithOracle(Specification spec, DocumentedExecutable targetMethod,
			HashMap<Integer, HashMap<String, IfStmt>> existingPrecondChecks, List<ExpressionStmt> methodCallsToEnrich,
			List<ThrowsSpecification> associatedThowSpecs, Map<DocumentedExecutable, OperationSpecification> allSpecs,
			int identifier, int specificationCounter) {
		String targetMethodName = targetMethod.getName().substring(targetMethod.getName().lastIndexOf('.') + 1);
		for (int i = 0; i < methodCallsToEnrich.size(); i++) {
			ExpressionStmt targetCall = methodCallsToEnrich.get(i);
			// Call for which we shall add the oracle
			NodeList<Statement> insertionPoint = null;
			try {
				insertionPoint = ((BlockStmt) targetCall.getParentNode().get()).getStatements();
			} catch (Exception e) {
				String targetCallWithAssignmentStmt = " _methodResult__ = " + targetCall;
				Statement targetCallWithAssignment = StaticJavaParser.parseStatement(targetCallWithAssignmentStmt);
				insertionPoint = ((BlockStmt) targetCallWithAssignment.getParentNode().get()).getStatements();
			}

			// Add precondition check
			String precondClause = "";
			String precondComment = "";
			for (PreSpecification precond : allSpecs.get(targetMethod).getPreSpecifications()) {
				precondClause = composeGuardClause(precond, targetCall, targetMethodName, precondClause);
				precondComment = precondComment + precond.getDescription() + precond.getGuard();
			}
			if (precondClause.isEmpty()) {
				precondClause = "true";
			}
			NodeList<Statement> precondSatisfiedInsertionPoint = null;
			if (existingPrecondChecks.containsKey(i) && existingPrecondChecks.get(i).containsKey(precondClause)) {
				if (precondClause.contains("SKIP_UNMODELED")) {
					identifier++;
					continue;
				}
				precondSatisfiedInsertionPoint = existingPrecondChecks.get(i).get(precondClause).getThenStmt()
						.asBlockStmt().getStatements();
			} else {
				// Skip unmodeled guard
				if (precondClause.contains("SKIP_UNMODELED")) {
					precondComment = "Skipped check due to unmodeled precondition: " + precondComment;
					addSkipClause(insertionPoint, targetCall, identifier, specificationCounter, "unmodeled",
							precondComment);
					identifier++;
					HashMap<String, IfStmt> fill = new HashMap<String, IfStmt>();
					fill.put(precondClause, null);
					existingPrecondChecks.put(i, fill);
					continue;
				}
				IfStmt precondIf = new IfStmt();
				precondIf.setCondition(StaticJavaParser.parseExpression(precondClause));
				BlockStmt precondSatisfiedBlock = new BlockStmt();
				precondIf.setThenStmt(precondSatisfiedBlock);
				precondSatisfiedBlock.addStatement(StaticJavaParser.parseStatement("satisfiedPreconds++;"));
				precondIf.setElseStmt(
						new BlockStmt().addStatement(StaticJavaParser.parseStatement("violatedPreconds++;")));
				precondComment = "Precondition: " + precondComment;
				precondIf.setLineComment(precondComment);
				insertionPoint.addBefore(precondIf, targetCall);
				precondSatisfiedInsertionPoint = precondSatisfiedBlock.getStatements();
				HashMap<String, IfStmt> fill = new HashMap<String, IfStmt>();
				fill.put(precondClause, precondIf);
				existingPrecondChecks.put(i, fill);
			}

			// Add postcondition oracle guard
			String clause = composeGuardClause(spec, targetCall, targetMethodName, "");

			// Skip non satisfiable guard
			if (clause.contains("null.")) {
				String comment = "Skipped check due to non satisfiable guard: " + clause + " " + spec.getDescription();
				addSkipClause(precondSatisfiedInsertionPoint, targetCall, identifier, specificationCounter,
						"not_executable", comment);
				identifier++;
				continue;
			}

			String clauseComment = "Guard: " + spec.getDescription() + "Condition: " + spec.getGuard();

			boolean unmodeledGuard = false;
			// Manage and mark unmodeled guard
			if (clause.contains("SKIP_UNMODELED")) {
				clauseComment = "Skipped check due to unmodeled guard: " + spec.getGuard() + " "
						+ spec.getDescription();
				clause = "true";
				unmodeledGuard = true;
			}

			// add assert
			if (spec instanceof PostSpecification) {
				PostSpecification postSpec = (PostSpecification) spec;
				if (postSpec.getProperty().getConditionText().isEmpty() && (clause.equals("true") || unmodeledGuard)) {
					log.error("* Method " + configuration.getTargetClass() + "." + targetMethod.getSignature()
							+ ": Skipping spec with both empty/unmodeled guard and empty/unmodeled post condition: "
							+ spec);
					continue;
				}

				addIfGuard(clause, targetCall, targetMethod.getReturnType().getType(), precondSatisfiedInsertionPoint,
						identifier, clauseComment);

				// the postCondition of the oracle, plus all postConditions with empty guards
				ExpressionStmt newTargetCall = addAssertClause(postSpec, unmodeledGuard, targetCall, insertionPoint,
						targetMethod, identifier, specificationCounter);
				methodCallsToEnrich.set(i, newTargetCall);
				targetCall = newTargetCall;
			} else if (spec instanceof ThrowsSpecification) {
				ThrowsSpecification throwSpec = (ThrowsSpecification) spec;
				if (throwSpec.getExceptionTypeName().isEmpty() && (clause.equals("true") || unmodeledGuard)) {
					log.error("* Method " + configuration.getTargetClass() + "." + targetMethod.getSignature()
							+ ": Skipping spec with both empty/unmodeled guard and empty/unmodeled post condition: "
							+ spec);
					continue;
				}
				addIfGuard(clause, targetCall, targetMethod.getReturnType().getType(), precondSatisfiedInsertionPoint,
						identifier, clauseComment);
				// the try-catch block to check for the expected
				addFailClause(throwSpec, unmodeledGuard, targetCall, insertionPoint, targetMethod, identifier,
						specificationCounter);
			} else {
				// exception
				throw new RuntimeException(
						"Spec of unexpected type " + spec.getClass().getName() + ": " + spec.getDescription());
			}
			identifier++;
		}
		return identifier;
	}

	private static void addSkipClause(NodeList<Statement> insertionPoint, ExpressionStmt targetCall, int identifier,
			int specificationCounter, String type, String comment) {

		Statement uniqueStmt = StaticJavaParser.parseStatement("uniqueGuardIds_lta.add(\"" + identifier + "\");");
		uniqueStmt.setComment(new LineComment(comment));
		insertionPoint.add(uniqueStmt);

		IfStmt ifContractStatus = new IfStmt();
		ifContractStatus.setCondition(StaticJavaParser
				.parseExpression("globalGuardsIds_lta.get(\"" + specificationCounter + "\").equals(\"not_executed\")"));
		ifContractStatus.setThenStmt(new BlockStmt()
				.addStatement("globalGuardsIds_lta.put(\"" + specificationCounter + "\",\"" + type + "\");"));
		insertionPoint.add(ifContractStatus);

	}

	private static void addIfGuard(String clause, ExpressionStmt targetCall, Type targetCallReturnType,
			NodeList<Statement> insertionPoint, int identifier, String guardsComment) {
		if (!clause.isEmpty()) {
			// Generated the if block containing the guard check
			Expression clauseExp = StaticJavaParser.parseExpression(clause);
			IfStmt i = new IfStmt();
			i.setCondition(clauseExp);

			BlockStmt bs = new BlockStmt();

			MethodCallExpr mce = new MethodCallExpr();
			mce.setScope(StaticJavaParser.parseExpression("uniqueGuardIds_lta"));
			mce.setName("add");
			mce.addArgument(new StringLiteralExpr(Integer.toString(identifier)));
			bs.addStatement(mce);

			bs.addStatement(StaticJavaParser.parseStatement("coveredContracts++;"));

			i.setThenStmt(bs);
			i.setLineComment(guardsComment);

			// Surround the block with try catch to prevent premature end of test cases
			// execution due to unsatisfied guard check that lead to the raising of an
			// exception/error
			TryStmt tryCatchGuard = new TryStmt();
			tryCatchGuard.setTryBlock(new BlockStmt().addStatement(i));
			CatchClause ccGuard = new CatchClause()
					.setParameter(new Parameter().setType("Throwable").setName("condEx"));
			NodeList<CatchClause> ccsGuard = new NodeList<CatchClause>();
			ccsGuard.add(ccGuard);
			tryCatchGuard.setCatchClauses(ccsGuard);

			// Add the generated guard to the test case code
			insertionPoint.add(tryCatchGuard);
		}
	}

	private static void addFailClause(ThrowsSpecification postCond, boolean unmodeled, ExpressionStmt targetCall,
			NodeList<Statement> insertionPoint, DocumentedExecutable targetMethod, int identifier,
			int specificationCounter) {
		String postCondCondition = postCond.getExceptionTypeName();
		if (postCondCondition == null || postCondCondition.isEmpty()) {
			if (postCond.getExceptionTypeName().isEmpty()) {
				log.error("* Method " + configuration.getTargetClass() + "." + targetMethod.getSignature()
						+ ": Post conditions has empty property for spec: " + postCond);
			} else {
				log.info("* Method " + configuration.getTargetClass() + "." + targetMethod.getSignature()
						+ ": Unmodeled postcondition for spec: " + postCond);
			}
			postCondCondition = "";
			unmodeled = true;
		}

		// TODO: here we have an unmodeled exception; consequently, if the unmodeled
		// part is the
		// postcondition itself while the guard exists, should we report it as a fail
		// since we know for sure that an exception wasn't raised?
		if (!unmodeled) {
			Statement assertStmt = StaticJavaParser.parseStatement(
					"if(uniqueGuardIds_lta.contains(\"" + identifier + "\")) {failedConds++;globalGuardsIds_lta.put(\""
							+ specificationCounter + "\",\"fail\");org.junit.Assert.fail();}");
			String comment = "Automatically generated test oracle:" + postCond.getDescription();
			assertStmt.setLineComment(comment);
			insertionPoint.addAfter(assertStmt, targetCall);
		}
		Optional<Node> n = insertionPoint.getParentNode();
		if (n.isPresent()) {
			n = n.get().getParentNode();
			if (n.isPresent() && (n.get() instanceof TryStmt)) {
				TryStmt ts = (TryStmt) n.get();
				for (CatchClause cc : ts.getCatchClauses()) {
					cc.getParameter().setType(Throwable.class);

					BlockStmt bs = cc.getBody();
					IfStmt ifUniqueGuard = new IfStmt();
					ifUniqueGuard.setCondition(
							StaticJavaParser.parseExpression("uniqueGuardIds_lta.contains(\"" + identifier + "\")"));
					if (unmodeled) {
						if (postCondCondition.isEmpty()) {
							ifUniqueGuard.setThenStmt(new BlockStmt().addStatement(StaticJavaParser.parseStatement(
									"globalGuardsIds_lta.put(\"" + specificationCounter + "\",\"unmodeled\");")));
						} else {
							IfStmt ifContractStatus = new IfStmt();
							ifContractStatus.setCondition(StaticJavaParser.parseExpression(
									cc.getParameter().getName() + " instanceof " + postCond.getExceptionTypeName()));
							ifContractStatus.setThenStmt(new BlockStmt().addStatement(StaticJavaParser.parseStatement(
									"globalGuardsIds_lta.put(\"" + specificationCounter + "\",\"unmodeled\");")));
							ifUniqueGuard.setThenStmt(new BlockStmt().addStatement(ifContractStatus));
						}
						String comment2 = "Added unmodeled test: " + postCond.getDescription();
						ifUniqueGuard.setLineComment(comment2);
					} else {
						IfStmt ifContractStatus = new IfStmt();
						ifContractStatus.setCondition(StaticJavaParser.parseExpression(
								cc.getParameter().getName() + " instanceof " + postCond.getExceptionTypeName()));
						IfStmt thenIfContractStatus = new IfStmt();
						thenIfContractStatus.setCondition(StaticJavaParser.parseExpression(
								"!globalGuardsIds_lta.get(\"" + specificationCounter + "\").equals(\"fail\")"));
						BlockStmt thenThenIfContractStatus = new BlockStmt();
						thenThenIfContractStatus.addStatement(
								StaticJavaParser.parseStatement("pass.add(\"" + specificationCounter + "\");"));
						thenIfContractStatus.setThenStmt(thenThenIfContractStatus);
						ifContractStatus.setThenStmt(new BlockStmt().addStatement(thenIfContractStatus));
						BlockStmt elseIfContractStatus = new BlockStmt();
						elseIfContractStatus.addStatement(
								StaticJavaParser.parseStatement("fail.add(\"" + specificationCounter + "\");"));
						ifContractStatus.setElseStmt(elseIfContractStatus);
						BlockStmt thenIfUniqueGuard = new BlockStmt();
						thenIfUniqueGuard.addStatement(ifContractStatus);
						thenIfUniqueGuard.addStatement("org.junit.Assert.assertTrue(" + cc.getParameter().getName()
								+ " instanceof " + postCond.getExceptionTypeName() + ");");
						ifUniqueGuard.setThenStmt(thenIfUniqueGuard);

						String comment2 = "Automatically generated test oracle: " + postCond.getDescription();
						ifUniqueGuard.setLineComment(comment2);
					}

					// Surround the block with try catch to prevent premature end of test cases
					// execution due to unsatisfied guard postcond exception check that lead to the
					// raising of an exception/error. This is done to prevent raising exceptions
					// when multiple preconditions are satisfied.
					TryStmt tryCatchGuard = new TryStmt();
					tryCatchGuard.setTryBlock(new BlockStmt().addStatement(ifUniqueGuard));
					CatchClause ccGuard = new CatchClause()
							.setParameter(new Parameter().setType("Throwable").setName("condEx"));
					ccGuard.setBody(
							new BlockStmt().addStatement(StaticJavaParser.parseStatement("raisedEx = condEx;")));
					NodeList<CatchClause> ccsGuard = new NodeList<CatchClause>();
					ccsGuard.add(ccGuard);
					tryCatchGuard.setCatchClauses(ccsGuard);

					bs.addStatement(tryCatchGuard);
					cc.setBody(bs);
				}
			}
		}
	}

	private static ExpressionStmt addAssertClause(PostSpecification postCond, boolean unmodeled,
			ExpressionStmt targetCall, NodeList<Statement> insertionPoint, DocumentedExecutable targetMethod,
			int identifier, int specificationCounter) {
		ExpressionStmt targetCallToConsider = targetCall;
		String postCondCondition = postCond.getProperty().getConditionText();
		if (postCondCondition == null || postCondCondition.isEmpty()) {
			if (postCond.getProperty().getDescription().isEmpty()) {
				log.error("* Method " + configuration.getTargetClass() + "." + targetMethod.getSignature()
						+ ": Post conditions has empty property for spec: " + postCond);
			} else {
				log.info("* Method " + configuration.getTargetClass() + "." + targetMethod.getSignature()
						+ ": Unmodeled postcondition for spec: " + postCond);
			}
			postCondCondition = "true";
			unmodeled = true;
		}

		String oracle = replaceFormalParamsWithActualValues(postCondCondition, targetCall);

		if (oracle.contains("_methodResult__") && !targetCall.toString().contains("_methodResult__ =")) {
			Type targetMethodReturnType = targetMethod.getReturnType().getType();
			// targetCall has a return value which is currently not assigned to any variable
			// in the test case
			String targetCallReturnTypeName = targetMethodReturnType.getTypeName();

			// Manage parametric returned type
			if (targetCallReturnTypeName.matches("[A-Z]+")) {
				Expression exp = targetCall.getExpression();
				if (exp.isMethodCallExpr()) {
					try {
						targetCallReturnTypeName = exp.asMethodCallExpr().resolve().getReturnType().erasure()
								.describe();
					} catch (UnsolvedSymbolException e) {
						log.warn(
								"Failure in symbol solving to determine actually returned parametric type. Object will be used as a fallback.");
						targetCallReturnTypeName = targetMethodReturnType.getTypeName().replaceAll("[A-Z]+", "Object");
					}
				} else {
					log.error("A constructor should not return any value. Error in expr: " + exp.toString());
				}
			}

			targetCallReturnTypeName = targetCallReturnTypeName.replaceAll("<[A-Za-z0-9_$? ]+>", "<?>");

			String assignStmt = targetCallReturnTypeName + " _methodResult__ = " + targetCall;
			try {
				ExpressionStmt targetCallWithAssignment = StaticJavaParser.parseStatement(assignStmt)
						.asExpressionStmt();
				Optional<Node> n = targetCall.getParentNode();
				try {
					Node parent = n.get();
					targetCallWithAssignment.setParentNode(parent);
					insertionPoint.replace(targetCall, targetCallWithAssignment);
					targetCallToConsider = targetCallWithAssignment;
				} catch (Exception e) {
					e.printStackTrace();
					log.error("Target call missing parent problem while adapting test case with statement: \n"
							+ "\n postcond is: " + postCond + "\n oracle is: " + oracle + "\n target call is: "
							+ targetCall + "\n adapted assignment statement is: " + assignStmt + "\n test case is: "
							+ insertionPoint.getParentNode());
				}
			} catch (Exception e) {
				log.error("Parse problem while adapting test case with statement: \n" + "\n postcond is: " + postCond
						+ "\n oracle is: " + oracle + "\n target call is: " + targetCall
						+ "\n adapted assignment statement is: " + assignStmt + "\n test case is: "
						+ insertionPoint.getParentNode());
			}
		}

		IfStmt ifUniqueGuard = new IfStmt();
		ifUniqueGuard
				.setCondition(StaticJavaParser.parseExpression("uniqueGuardIds_lta.contains(\"" + identifier + "\")"));
		IfStmt ifContractStatus = new IfStmt();
		ifContractStatus.setCondition(StaticJavaParser.parseExpression(oracle));
		// If postcondition guard or postcondition are unmodeled we simply set their
		// status to unmodeled since we have no way of determining potential failure or
		// pass.
		if (unmodeled) {
			ifContractStatus.setThenStmt(new BlockStmt().addStatement(StaticJavaParser
					.parseStatement("globalGuardsIds_lta.put(\"" + specificationCounter + "\",\"unmodeled\");")));
			String comment = "Added unmodeled test: " + postCond.getDescription();
			ifUniqueGuard.setThenStmt(new BlockStmt().addStatement(ifContractStatus));
			ifUniqueGuard.setLineComment(comment);
		} else {
			IfStmt thenIfContractStatus = new IfStmt();
			thenIfContractStatus.setCondition(StaticJavaParser
					.parseExpression("!globalGuardsIds_lta.get(\"" + specificationCounter + "\").equals(\"fail\")"));
			BlockStmt thenThenIfContractStatus = new BlockStmt();
			thenThenIfContractStatus.addStatement(StaticJavaParser
					.parseStatement("globalGuardsIds_lta.put(\"" + specificationCounter + "\",\"pass\");"));
			thenThenIfContractStatus.addStatement("passedConds++;");
			thenIfContractStatus.setThenStmt(thenThenIfContractStatus);
			ifContractStatus.setThenStmt(new BlockStmt().addStatement(thenIfContractStatus));
			BlockStmt elseIfContractStatus = new BlockStmt();
			elseIfContractStatus.addStatement(StaticJavaParser
					.parseStatement("globalGuardsIds_lta.put(\"" + specificationCounter + "\",\"fail\");"));
			elseIfContractStatus.addStatement(StaticJavaParser.parseStatement("failedConds++;"));
			ifContractStatus.setElseStmt(elseIfContractStatus);
			BlockStmt thenIfUniqueGuard = new BlockStmt();
			thenIfUniqueGuard.addStatement(ifContractStatus);
			thenIfUniqueGuard.addStatement("org.junit.Assert.assertTrue(" + oracle + ");");
			ifUniqueGuard.setThenStmt(thenIfUniqueGuard);
			String comment = "Automatically generated test oracle: " + postCond.getDescription();
			ifUniqueGuard.setLineComment(comment);
		}

		// Surround the block with try catch to prevent premature end of test cases
		// execution due to unsatisfied guard postcond check that lead to the raising of
		// an exception/error. This should happen only in the case of Toradocu creating
		// a faulty assertion
		TryStmt tryCatchGuard = new TryStmt();
		tryCatchGuard.setTryBlock(new BlockStmt().addStatement(ifUniqueGuard));
		CatchClause ccGuard = new CatchClause().setParameter(new Parameter().setType("Throwable").setName("condEx"));
		IfStmt ifCatchBody = new IfStmt();
		ifCatchBody.setCondition(StaticJavaParser.parseExpression("condEx instanceof java.lang.AssertionError"));
		ifCatchBody.setThenStmt(StaticJavaParser.parseStatement("throw condEx;"));
		ccGuard.setBody(new BlockStmt().addStatement(ifCatchBody));
		NodeList<CatchClause> ccsGuard = new NodeList<CatchClause>();
		ccsGuard.add(ccGuard);
		tryCatchGuard.setCatchClauses(ccsGuard);

		// Add the postcondition guard check to the test case
		insertionPoint.addAfter(tryCatchGuard, targetCallToConsider);

		// If the method invoked in the test case is surrounded with a try catch block
		// we need to add the postcondition check inside of the catch block and rise an
		// error if the guard was satisfied, since no exception should be raised for
		// assertion checks.
		// Nothing should be done in case we have unmodeled postcondition guard or
		// postcondition.
		if (!unmodeled) {
			Optional<Node> n = insertionPoint.getParentNode();
			if (n.isPresent()) {
				n = n.get().getParentNode();
				if (n.isPresent() && (n.get() instanceof TryStmt)) {
					TryStmt ts = (TryStmt) n.get();
					for (CatchClause cc : ts.getCatchClauses()) {
						BlockStmt bs = cc.getBody();
						Statement assertStmt2 = StaticJavaParser
								.parseStatement("if(uniqueGuardIds_lta.contains(\"" + identifier + "\")) {fail.add(\""
										+ specificationCounter + "\");org.junit.Assert.fail();}");
						String comment2 = "Automatically generated test oracle:" + postCond.getDescription();
						assertStmt2.setLineComment(comment2);

						// Surround the block with try catch to prevent premature end of test cases
						// execution due to unsatisfied guard postcond exception check that lead to the
						// raising of an exception/error. This is done to prevent raising exceptions
						// when multiple preconditions are satisfied.
						TryStmt tryCatchFail = new TryStmt();
						tryCatchFail.setTryBlock(new BlockStmt().addStatement(assertStmt2));
						CatchClause ccFail = new CatchClause()
								.setParameter(new Parameter().setType("Throwable").setName("condEx"));
						ccFail.setBody(
								new BlockStmt().addStatement(StaticJavaParser.parseStatement("raisedEx = condEx;")));
						NodeList<CatchClause> ccsFail = new NodeList<CatchClause>();
						ccsFail.add(ccFail);
						tryCatchFail.setCatchClauses(ccsFail);

						bs.addStatement(tryCatchFail);
						cc.setBody(bs);
					}
				}
			}
		}
		return targetCallToConsider;
	}

	private static String composeGuardClause(Specification spec, ExpressionStmt targetCall, String targetMethodName,
			String existingClause) {
		String guard = spec.getGuard().getConditionText();
		String condToAssume = null;
		if (guard != null && !guard.isEmpty()) {
			condToAssume = replaceFormalParamsWithActualValues(guard, targetCall);
		} else if (!(spec instanceof PreSpecification)) {
			if (!spec.getGuard().getDescription().isEmpty()) {
				return "SKIP_UNMODELED";
			} else {
				// According to Toradocu, this oracle has no guard (no condition/description
				// were identified)
				condToAssume = "true";
			}
		}
		if (condToAssume != null) {
			if (existingClause.isEmpty()) {
				existingClause = condToAssume;
			} else {
				existingClause = existingClause + "&&" + condToAssume;
			}
		}
		return existingClause;
	}

	private static String replaceFormalParamsWithActualValues(String guardString, ExpressionStmt callStmt) {
		String ret = guardString;

		// replace receiverObject with receiver from target
		Expression callExpr = callStmt.getExpression();
		if (callExpr.isVariableDeclarationExpr()) {
			callExpr = callExpr.asVariableDeclarationExpr().getVariable(0).getInitializer().get();
		} else if (callExpr.isAssignExpr()) {
			callExpr = callExpr.asAssignExpr().getValue();
		}
		if (!callExpr.isMethodCallExpr() && !callExpr.isObjectCreationExpr()) {
			// Manage case in which the return type is explicitly casted
			if (callExpr.isCastExpr()) {
				callExpr = callExpr.asCastExpr().getExpression();
			}
			if (!callExpr.isMethodCallExpr() && !callExpr.isObjectCreationExpr()) {
				throw new RuntimeException("This type of target call is not handled yet: " + callExpr);
			}
		}
		if (callExpr.isMethodCallExpr() && callExpr.asMethodCallExpr().getScope().isPresent()) {
			ret = ret.replace("receiverObjectID", callExpr.asMethodCallExpr().getScope().get().toString());
		}
		if (callExpr.isObjectCreationExpr() && ret.contains("receiverObjectID")) {
			ret = ret.replace("receiverObjectID", callExpr.asObjectCreationExpr().getTypeAsString());
		}

		// replace methodResult with result from target
		if (callStmt.getExpression().isVariableDeclarationExpr()) {
			ret = ret.replace("methodResultID",
					callStmt.getExpression().asVariableDeclarationExpr().getVariable(0).getName().toString());
		} else if (callStmt.getExpression().isAssignExpr()) {
			ret = ret.replace("methodResultID",
					callStmt.getExpression().asAssignExpr().getTarget().asNameExpr().getName().toString());
		} else if (ret.contains("methodResultID")) {
			ret = ret.replace("methodResultID", "_methodResult__");
		}

		// replace args[i] with arguments from target
		int index = 0;
		NodeList<Expression> args = callExpr.isMethodCallExpr() ? callExpr.asMethodCallExpr().getArguments()
				: callExpr.asObjectCreationExpr().getArguments();
		for (Expression arg : args) {
			if (arg.isCastExpr()) {
				ret = ret.replace("args[" + index + "]", "(" + arg.toString() + ")");
			} else {
				ret = ret.replace("args[" + index + "]", /* "((" + type + ") */ arg.toString());
			}
			++index;
		}
		return ret;
	}

	/**
	 * Creates and launches an external process.
	 * 
	 * @param commandLine a {@link List}{@code <}{@link String}{@code >}, the
	 *                    command line to launch the process in the format expected
	 *                    by {@link ProcessBuilder}.
	 * @param logFilePath a {@link Path} to a log file where stdout and stderr of
	 *                    the process will be redirected.
	 * @return the created {@link Process}.
	 * @throws IOException if thrown by {@link ProcessBuilder#start()}.
	 */
	private static Process launchProcess(List<String> commandLine, Path logFilePath) throws IOException {
		final ProcessBuilder pb = new ProcessBuilder(commandLine).redirectErrorStream(true)
				.redirectOutput(logFilePath.toFile());
		final Process pr = pb.start();
		return pr;
	}

	/**
	 * Builds the command line for invoking EvoSuite.
	 * 
	 * @param evaluatorDefsForEvoSuite
	 * @param outputDir
	 * @param testsDir
	 * 
	 * @return a command line in the format of a
	 *         {@link List}{@code <}{@link String}{@code >}, suitable to be passed
	 *         to a {@link ProcessBuilder}.
	 */
	private static List<String> buildEvoSuiteCommand(Path outputDir, int evosuiteBudget) {
		final String targetClass = configuration.getTargetClass();
		final List<String> retVal = new ArrayList<String>();
		String classpathTarget = outputDir.toString();
		for (URL cp : configuration.classDirs) {
			classpathTarget += ":" + cp.getPath();
		}
		retVal.add(configuration.getJava8path());
		// retVal.add("-Xmx16G");
		retVal.add("-jar");
		retVal.add(configuration.getEvoSuiteJar());
		retVal.add("-class");
		retVal.add(targetClass);
		retVal.add("-mem");
		retVal.add("4096");
		retVal.add("-DCP=" + classpathTarget);
		retVal.add("-Dassertions=false");
		// retVal.add("-Dsearch_budget=" + configuration.getEvoSuiteBudget());
		retVal.add("-Dsearch_budget=" + evosuiteBudget);
		retVal.add("-Dreport_dir=" + outputDir);
		retVal.add("-Dtest_dir=" + outputDir);
		retVal.add("-Dvirtual_fs=true");
		retVal.add("-Dcriterion=LINE:BRANCH:EXCEPTION:WEAKMUTATION:OUTPUT:METHOD:METHODNOEXCEPTION:CBRANCH");
		// retVal.add("-Dno_runtime_dependency");
		// enabled assertions since evosuite is generating failing test cases for them
		// retVal.add("-ea");
		return retVal;
	}

	/**
	 * Creates the directory specified by {@code outputDir}.
	 *
	 * @param outputDir the directory to be created
	 * @return {@code true} if the creation succeeded, {@code false} otherwise
	 */
	private static boolean createOutputDir(String outputDir, boolean clear) {
		boolean creationSucceeded = true;
		final File outDir = new File(outputDir);
		if (outDir.exists()) {
			if (clear) {
				try {
					FileUtils.deleteDirectory(outDir);
				} catch (IOException e) {
					log.error("Error while deleting previous content of folder: " + outputDir);
				}
			}
		}
		if (!outDir.exists()) {
			creationSucceeded = outDir.mkdirs();
		}
		if (!creationSucceeded) {
			log.error("Error during creation of directory: " + outputDir);
		}
		return creationSucceeded;
	}

}
