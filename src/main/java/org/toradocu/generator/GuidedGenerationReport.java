package org.toradocu.generator;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map.Entry;

import org.apache.commons.lang3.StringEscapeUtils;

import java.util.Objects;

import randoop.condition.specification.Specification;

public class GuidedGenerationReport {

	public HashMap<String, HashMap<String, String>> classTestCaseContractStatus;
	public HashMap<String, ContractStatus> classContractStatus;
	public HashMap<ContractKey, String> contractsStatus;

	public GuidedGenerationReport() {
		super();
		classTestCaseContractStatus = new HashMap<String, HashMap<String, String>>();
		classContractStatus = new HashMap<String, ContractStatus>();
		contractsStatus = new HashMap<ContractKey, String>();
	}

	public void buildReport(Path testsDir, String testName, String targetClass, String testedMethodName,
			Specification spec) {
		final Path testCaseAbsPath = testsDir.resolve(testName.replace('.', File.separatorChar) + ".java");
		File currentTestCase = new File(testCaseAbsPath.toUri());
		buildClassLevelStats(currentTestCase, testName, targetClass);
		buildContractLevelStats(currentTestCase, targetClass, testedMethodName, spec);
	}

	private void buildContractLevelStats(File currentTestCase, String targetClass, String testedMethodName,
			Specification spec) {
		String specString = StringEscapeUtils.escapeJava(spec.toString());
		ContractKey key = new ContractKey(targetClass, testedMethodName, specString);
		String status;
		if (contractsStatus.containsKey(key)) {
			status = contractsStatus.get(key);
		} else {
			status = new String();
		}
		if (currentTestCase.exists() && currentTestCase.toString().contains("failure_Test"))
			status = "fail";
		else if (currentTestCase.exists() && !currentTestCase.toString().contains("unmodeled_Test")
				&& !status.equals("fail")) {
			status = "pass";
		} else if (currentTestCase.exists() && currentTestCase.toString().contains("unmodeled_Test")
				&& !status.equals("fail") && !status.equals("pass")) {
			status = "unmodeled";
		} else if (!currentTestCase.exists() && status.equals("")) {
			status = "not_present";
		}
		contractsStatus.put(key, status);
	}

	private void buildClassLevelStats(File currentTestCase, String testName, String targetClass) {
		HashMap<String, String> testCaseContractStatus;
		if (!classTestCaseContractStatus.containsKey(targetClass)) {
			testCaseContractStatus = new HashMap<String, String>();
		} else {
			testCaseContractStatus = classTestCaseContractStatus.get(targetClass);
		}
		String currentTestCaseNoSuffix = testName.replace("failure_Test", "Test").replace("unmodeled_Test", "Test");

		String status;
		if (testCaseContractStatus.containsKey(currentTestCaseNoSuffix)) {
			status = testCaseContractStatus.get(currentTestCaseNoSuffix);
		} else {
			status = new String();
		}
		if (currentTestCase.exists() && currentTestCase.toString().contains("failure_Test"))
			status = "fail";
		else if (currentTestCase.exists() && !currentTestCase.toString().contains("unmodeled_Test")
				&& !status.equals("fail")) {
			status = "pass";
		} else if (currentTestCase.exists() && currentTestCase.toString().contains("unmodeled_Test")
				&& !status.equals("fail") && !status.equals("pass")) {
			status = "unmodeled";
		} else if (!currentTestCase.exists() && status.equals("")) {
			status = "not_present";
		}
		testCaseContractStatus.put(currentTestCaseNoSuffix, status);
		classTestCaseContractStatus.put(targetClass, testCaseContractStatus);
	}

	private void generateContractLevelReport() {
		for (Entry<ContractKey, String> entry : contractsStatus.entrySet()) {
			String clax = entry.getKey().getClax();
			String method = entry.getKey().getMethod();
			String contract = entry.getKey().getSpec();
			File myObj = new File("report-contract-level.csv");
			try {
				if (!myObj.exists()) {
					myObj.createNewFile();
					FileWriter myWriter = new FileWriter(myObj);
					myWriter.write(
							"class" + ";" + "method" + ";" + "contract" + ";" + "status" + System.lineSeparator());
					myWriter.close();
				}
				FileWriter myWriter = new FileWriter(myObj, true);
				myWriter.write("\"" + clax + "\"" + ";" + "\"" + method + "\"" + ";" + "\"" + contract + "\"" + ";"
						+ "\"" + entry.getValue() + "\"" + ";" + System.lineSeparator());
				myWriter.close();
			} catch (IOException e) {
				System.out.println("An error occurred during report generation.");
				e.printStackTrace();
			}
		}
		System.out.println("Successfully wrote contract level report.");
	}

	private void computeClassLevelStats() {
		for (Entry<String, HashMap<String, String>> cl : classTestCaseContractStatus.entrySet()) {
			String clax = cl.getKey();
			ContractStatus claxStatuses = new ContractStatus();
			for (String status : cl.getValue().values()) {
				if (status.equals("fail")) {
					claxStatuses.setFail(claxStatuses.getFail() + 1);
				} else if (status.equals("pass")) {
					claxStatuses.setPass(claxStatuses.getPass() + 1);
				} else if (status.equals("unmodeled")) {
					claxStatuses.setUnmodeled(claxStatuses.getUnmodeled() + 1);
				} else {
					claxStatuses.setNot_present(claxStatuses.getNot_present() + 1);
				}
			}
			classContractStatus.put(clax, claxStatuses);
		}
	}

	private void generateClassLevelReport() {
		computeClassLevelStats();
		File myObj = new File("report-class-level.csv");
		try {
			if (!myObj.exists()) {
				myObj.createNewFile();
				FileWriter myWriter = new FileWriter(myObj);
				myWriter.write("class" + ";" + "unmodeled" + ";" + "notPresent" + ";" + "notExecutable" + ";"
						+ "notExecuted" + ";" + "pass" + ";" + "fail" + System.lineSeparator());
				myWriter.close();
			}
			for (Entry<String, ContractStatus> classReportField : classContractStatus.entrySet()) {
				String className = classReportField.getKey();
				ContractStatus reportField = classReportField.getValue();
				int notExecuted = reportField.getNot_executed();
				int notPresent = reportField.getNot_present();
				int pass = reportField.getPass();
				int fail = reportField.getFail();
				int notExecutable = reportField.getNot_executable();
				int unmodeled = reportField.getUnmodeled();

				FileWriter myWriter = new FileWriter(myObj, true);
				myWriter.write(className + ";" + unmodeled + ";" + notPresent + ";" + notExecutable + ";" + notExecuted
						+ ";" + pass + ";" + fail + System.lineSeparator());
				myWriter.close();
				System.out.println("Successfully wrote class level report.");
			}
		} catch (IOException e) {
			System.out.println("An error occurred during report generation.");
			e.printStackTrace();
		}
	}

	public void generateReport() {
		generateClassLevelReport();
		generateContractLevelReport();
	}
}

class ContractKey {
	private String clax;
	private String method;
	private String spec;

	public ContractKey(String clax, String method, String spec) {
		super();
		this.clax = clax;
		this.method = method;
		this.spec = spec;
	}

	/**
	 * @return the clax
	 */
	public String getClax() {
		return clax;
	}

	/**
	 * @param clax the clax to set
	 */
	public void setClax(String clax) {
		this.clax = clax;
	}

	/**
	 * @return the method
	 */
	public String getMethod() {
		return method;
	}

	/**
	 * @param method the method to set
	 */
	public void setMethod(String method) {
		this.method = method;
	}

	/**
	 * @return the spec
	 */
	public String getSpec() {
		return spec;
	}

	/**
	 * @param spec the spec to set
	 */
	public void setSpec(String spec) {
		this.spec = spec;
	}

	@Override
	public int hashCode() {
		return Objects.hash(clax, method, spec);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		ContractKey other = (ContractKey) obj;
		return Objects.equals(clax, other.clax) && Objects.equals(method, other.method)
				&& Objects.equals(spec, other.spec);
	}
}

class ContractStatus {
	private int not_executed;
	private int not_present;
	private int pass;
	private int fail;
	private int not_executable;
	private int unmodeled;

	public ContractStatus() {
		super();
		not_executed = 0;
		not_present = 0;
		pass = 0;
		fail = 0;
		not_executable = 0;
		unmodeled = 0;
	}

	/**
	 * @return the not_executed
	 */
	public int getNot_executed() {
		return not_executed;
	}

	/**
	 * @return the not_present
	 */
	public int getNot_present() {
		return not_present;
	}

	/**
	 * @return the pass
	 */
	public int getPass() {
		return pass;
	}

	/**
	 * @return the fail
	 */
	public int getFail() {
		return fail;
	}

	/**
	 * @return the not_executable
	 */
	public int getNot_executable() {
		return not_executable;
	}

	/**
	 * @return the unmodeled
	 */
	public int getUnmodeled() {
		return unmodeled;
	}

	/**
	 * @param not_executed the not_executed to set
	 */
	public void setNot_executed(int not_executed) {
		this.not_executed = not_executed;
	}

	/**
	 * @param not_present the not_present to set
	 */
	public void setNot_present(int not_present) {
		this.not_present = not_present;
	}

	/**
	 * @param pass the pass to set
	 */
	public void setPass(int pass) {
		this.pass = pass;
	}

	/**
	 * @param fail the fail to set
	 */
	public void setFail(int fail) {
		this.fail = fail;
	}

	/**
	 * @param not_executable the not_executable to set
	 */
	public void setNot_executable(int not_executable) {
		this.not_executable = not_executable;
	}

	/**
	 * @param unmodeled the unmodeled to set
	 */
	public void setUnmodeled(int unmodeled) {
		this.unmodeled = unmodeled;
	}

}
