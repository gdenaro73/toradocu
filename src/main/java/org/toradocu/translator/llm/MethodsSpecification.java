package org.toradocu.translator.llm;

import java.util.List;

public class MethodsSpecification {

	private String className;

	private List<MethodDetails> methods;

	public MethodsSpecification() {
	}

	public MethodsSpecification(String className, List<MethodDetails> methods) {
		this.className = className;
		this.methods = methods;
	}

	public String getClassName() {
		return className;
	}

	public void setClassName(String className) {
		this.className = className;
	}

	public List<MethodDetails> getMethods() {
		return methods;
	}

	public void setMethods(List<MethodDetails> methods) {
		this.methods = methods;
	}

	// ================= MethodDetails =================
	public static class MethodDetails {
		private String signature;
		private List<PreSpecification> preSpecifications;
		private List<PostSpecification> postSpecifications;
		private List<ThrowSpecification> throwSpecifications;

		public MethodDetails() {
		}

		public MethodDetails(String signature, List<PreSpecification> preSpecifications,
				List<PostSpecification> postSpecifications, List<ThrowSpecification> throwSpecifications) {
			this.signature = signature;
			this.preSpecifications = preSpecifications;
			this.postSpecifications = postSpecifications;
			this.throwSpecifications = throwSpecifications;
		}

		public String getSignature() {
			return signature;
		}

		public void setSignature(String signature) {
			this.signature = signature;
		}

		public List<PreSpecification> getPreSpecifications() {
			return preSpecifications;
		}

		public void setPreSpecifications(List<PreSpecification> preSpecifications) {
			this.preSpecifications = preSpecifications;
		}

		public List<PostSpecification> getPostSpecifications() {
			return postSpecifications;
		}

		public void setPostSpecifications(List<PostSpecification> postSpecifications) {
			this.postSpecifications = postSpecifications;
		}

		public List<ThrowSpecification> getThrowSpecifications() {
			return throwSpecifications;
		}

		public void setThrowSpecifications(List<ThrowSpecification> throwSpecifications) {
			this.throwSpecifications = throwSpecifications;
		}
	}

	// ================= Base Specification =================
	public static class Specification {
		private String description;
		private String conditionText;
		private boolean successfullyTranslated;

		public Specification() {
		}

		public Specification(String description, String conditionText, boolean successfullyTranslated) {
			this.description = description;
			this.conditionText = conditionText;
			this.successfullyTranslated = successfullyTranslated;
		}

		public String getDescription() {
			return description;
		}

		public void setDescription(String description) {
			this.description = description;
		}

		public String getConditionText() {
			return conditionText;
		}

		public void setConditionText(String conditionText) {
			this.conditionText = conditionText;
		}

		public boolean isSuccessfullyTranslated() {
			return successfullyTranslated;
		}

		public void setSuccessfullyTranslated(boolean successfullyTranslated) {
			this.successfullyTranslated = successfullyTranslated;
		}

	}

	public static class PreSpecification extends Specification {
		public PreSpecification() {
			super();
		}

		public PreSpecification(String description, String conditionText, boolean successfullyTranslated) {
			super(description, conditionText, successfullyTranslated);
		}
	}

	public static class PostSpecification extends Specification {
		private String expectedResult;

		public PostSpecification() {
			super();
		}

		public PostSpecification(String description, String conditionText, boolean successfullyTranslated,
				String expectedResult) {
			super(description, conditionText, successfullyTranslated);
			this.expectedResult = expectedResult;
		}

		public String getExpectedResult() {
			return expectedResult;
		}

		public void setExpectedResult(String expectedResult) {
			this.expectedResult = expectedResult;
		}
	}

	public static class ThrowSpecification extends Specification {
		private String exceptionType;

		public ThrowSpecification() {
			super();
		}

		public ThrowSpecification(String description, String conditionText, boolean successfullyTranslated,
				String exceptionType) {
			super(description, conditionText, successfullyTranslated);
			this.exceptionType = exceptionType;
		}

		public String getExceptionType() {
			return exceptionType;
		}

		public void setExceptionType(String exceptionType) {
			this.exceptionType = exceptionType;
		}
	}
}