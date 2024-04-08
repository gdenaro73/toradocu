package org.toradocu.testlib;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.toradocu.Toradocu;
import org.toradocu.generator.TestGeneratorSummaryData;
import org.toradocu.generator.TestGenerator;

/**
 * PrecisionRecallTest contains static methods to perform a precision recall
 * test using Toradocu.
 */
public class TestgenTestValidation {

	/** Directory where test results are saved. */
	public static final String OUTPUT_DIR = "generated-tests/testgen-experiments-results";
	public static final String TESTGEN_OUTPUT_DIR = OUTPUT_DIR + "/validation-tests-data/"
			//+ "20240307";
			+ DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(java.time.LocalDateTime.now());
	public static final String evosuiteJar = "lib-evosuite/evosuite-shaded-1.2.1-SNAPSHOT.jar";
	/** The directory containing the source files on which to run tests. */
	private final String srcPath;
	/** The directory containing the binaries on which to run tests. */
	private final String binPath;
	/** The directory containing the goal output of the tests. */
	private final String goalOutputDir;
	/** The name of the analyzed project. */
	private final String projectName;

	public TestgenTestValidation(String sourceDirPath, String binDirPath, String goalOutputDirPath,
			String projectName) {
		this.srcPath = sourceDirPath;
		this.binPath = binDirPath;
		this.goalOutputDir = goalOutputDirPath;
		this.projectName = projectName;
		// Add bin to project folder
		try {
			String destinationFolder = TESTGEN_OUTPUT_DIR + "/" + projectName + "/";
			new File(destinationFolder).mkdirs();
			Files.copy(Paths.get(binDirPath),
					Paths.get(destinationFolder + binDirPath.substring(binDirPath.lastIndexOf("/"))),
					StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/** Creates the temporary directory if it does not exist. */
	@BeforeClass
	public static void setUp() throws IOException {
		new File(OUTPUT_DIR).mkdir();
	}

	/** Prints the results (i.e. statistics) of the test suite. */
	@AfterClass
	public static void tearDown() {
		TestGeneratorSummaryData._I().hline();
		TestGeneratorSummaryData._I().printTable();
	}

	/**
	 * Runs Toradocu on the given class and collects data on its precision and
	 * recall.
	 *
	 * @param targetClass   the fully qualified name of the class on which to run
	 *                      the test
	 * @param srcPath       the source path for the given targetClass
	 * @param binPath       the path to the binaries for the given targetClass
	 * @param goalOutputDir the path of the directory containing the goal output for
	 *                      the targetClass.
	 * @return statistics for the test
	 */
	public void test(String targetClass) {
		System.out.println("Starting experiment on class " + targetClass);
		String actualOutputFile = OUTPUT_DIR + File.separator + targetClass + "_out.json";
		String goalOutputFile = Paths.get(goalOutputDir, targetClass + "_goal.json").toString();
		String testOutDir = TESTGEN_OUTPUT_DIR + File.separator + projectName + File.separator
				+ targetClass.replace('/', '.');
		String[] toradocuArgs = new String[] { 
				"--target-class", 
				targetClass, 
				"--condition-translator-output",
				actualOutputFile, 
				"--expected-output", 
				goalOutputFile, 
				"--class-dir", 
				binPath, 
				"--source-dir", 
				srcPath,
				"--test-output-dir", 
				testOutDir, 
				"--evosuite-jar", 
				evosuiteJar, 
				"--evosuite-budget", 
				"60",
				"--test-generation",
				"false",
				"--validate-tests", 
				"true",
				"--validation-tests-skip-generation",
				"false",
				"--validation-tests-generate-backup",
				"true"};
		
		final List<String> argsList = new ArrayList<>(Arrays.asList(toradocuArgs));

		final String oracleGeneration = System.getProperty("org.toradocu.generator");
		// Disable oracle generation unless the specific system property is set.
		if (oracleGeneration != null && oracleGeneration.equals("true")) {
			argsList.add("--aspects-output-dir");
			argsList.add("aspects" + File.separator + targetClass);
		} else {
			argsList.add("--oracle-generation");
			argsList.add("false");
		}

		final String translator = System.getProperty("org.toradocu.translator");
		if (translator != null && translator.equals("tcomment")) {
			argsList.add("--tcomment");
			argsList.add("--stats-file");
			argsList.add("results_tcomment_.csv");
		} else if (translator != null && translator.equals("nosemantics")) {
			argsList.add("--disable-semantics");
			argsList.add("true");
			argsList.add("--stats-file");
			argsList.add("results_.csv");
		} else {
			// Semantic-based translator enabled by default.
			argsList.add("--stats-file");
			argsList.add("results_semantics_.csv");
		}

		Toradocu.main(argsList.toArray(new String[0]));

		final Path evaluatorsDir = Paths.get(testOutDir).resolve(TestGenerator.EVALUATORS_FOLDER);
		int i = 0;
		while (true) {
			final Path evosuiteLogFilePath = evaluatorsDir.resolve("evosuite-log-" + i + ".txt");
			File logFile = new File(evosuiteLogFilePath.toString());
			if (logFile.exists()) {
				TestGeneratorSummaryData._I().addEvosuiteData(logFile);
			} else {
				break;
			}
			++i;
		}
		TestGeneratorSummaryData._I().addCurrentSummaryAsTableRow(targetClass);
	}

}
