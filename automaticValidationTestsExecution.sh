TORADOCU_MAIN_FOLDER=$(pwd)
EVOSUITE_LIB_PATH=$TORADOCU_MAIN_FOLDER/lib-evosuite/evosuite-shaded-1.1.1-SNAPSHOT.jar
TEST_UTILS_LIB_PATH=$TORADOCU_MAIN_FOLDER/TestUtils.jar
BASE_FOLDER=$TORADOCU_MAIN_FOLDER/generated-tests/testgen-experiments-results/validation-tests-data

cd $BASE_FOLDER

shopt -s dotglob
shopt -s nullglob
experiments=(*/)
for exp in "${experiments[@]}"; do
	cd $exp
	projects=(*/)
	for project in "${projects[@]}"; do
		cd $project
		echo "Starting compilation for: "$(pwd)
		libs="${project//[\/]/.jar}":$EVOSUITE_LIB_PATH:$TEST_UTILS_LIB_PATH
		echo $libs
		find -name "*.java" > sources.txt
		javac -cp $libs @sources.txt
		testClassesFilePath="$BASE_FOLDER"/"$exp""$project"sources.txt
		echo "Finished compilation."
		while IFS= read -r testClass
		do
			if [[ $testClass != *"_scaffolding.java"* ]];
			then
				testClass=${testClass:2}
				testClass="${testClass/.java/""}"
				testClass="${testClass//[\/]/.}"
				echo "$testClass"
				java -cp .:"${project//[\/]/.jar}":$EVOSUITE_LIB_PATH:$TEST_UTILS_LIB_PATH org.junit.runner.JUnitCore $testClass
			fi
		done < $testClassesFilePath
		cd ..
	done
	cd ..
done
