package maestro.cli.runner

import maestro.orchestra.DescribeCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.TestCaseCommand
import java.util.IdentityHashMap

/**
 * Prints a hierarchical test summary at the end of test execution.
 * Only prints if describe/it commands are used in the flow.
 */
object TestHierarchySummary {

    data class TestResult(
        val name: String,
        val passed: Boolean,
        val stepCount: Int = 0
    )

    data class SuiteResult(
        val name: String,
        val tests: MutableList<TestResult> = mutableListOf()
    ) {
        val passed: Boolean get() = tests.all { it.passed }
        val passedCount: Int get() = tests.count { it.passed }
        val failedCount: Int get() = tests.count { !it.passed }
    }

    /**
     * Analyzes commands and their statuses to build hierarchical results.
     * Returns null if no describe/it commands are found.
     */
    fun buildResults(
        flowName: String,
        commands: List<MaestroCommand>,
        commandStatuses: Map<MaestroCommand, CommandStatus>
    ): List<SuiteResult>? {
        val suites = mutableListOf<SuiteResult>()
        var currentSuite: SuiteResult? = null
        var hasDescribeOrIt = false

        for (command in commands) {
            when (val cmd = command.asCommand()) {
                is DescribeCommand -> {
                    hasDescribeOrIt = true
                    currentSuite = SuiteResult(name = cmd.description)
                    suites.add(currentSuite)
                }
                is TestCaseCommand -> {
                    hasDescribeOrIt = true
                    val status = commandStatuses[command] ?: CommandStatus.PENDING
                    val passed = status == CommandStatus.COMPLETED || status == CommandStatus.SKIPPED
                    val testResult = TestResult(
                        name = cmd.testName,
                        passed = passed,
                        stepCount = cmd.steps.size
                    )
                    if (currentSuite != null) {
                        currentSuite.tests.add(testResult)
                    } else {
                        // Test without suite - create default suite
                        val defaultSuite = SuiteResult(name = flowName)
                        defaultSuite.tests.add(testResult)
                        suites.add(defaultSuite)
                        currentSuite = defaultSuite
                    }
                }
                else -> { /* ignore other commands */ }
            }
        }

        return if (hasDescribeOrIt) suites else null
    }

    /**
     * Prints the hierarchical summary to console.
     */
    fun printSummary(flowName: String, suites: List<SuiteResult>) {
        val totalTests = suites.sumOf { it.tests.size }
        val passedTests = suites.sumOf { it.passedCount }
        val failedTests = suites.sumOf { it.failedCount }
        val passedSuites = suites.count { it.passed }
        val failedSuites = suites.count { !it.passed }

        println()
        println("===== Test Summary =====")
        println()

        // Flow name
        val flowIcon = if (failedTests == 0) "✅" else "❌"
        println("$flowIcon $flowName")

        // Suites and tests
        for (suite in suites) {
            val suiteIcon = if (suite.passed) "✅" else "❌"
            println("  $suiteIcon ${suite.name}")

            for (test in suite.tests) {
                val testIcon = if (test.passed) "✅" else "❌"
                println("    $testIcon ${test.name}")
            }
        }

        println()
        println("========================")
        println("Suites: $passedSuites passed, $failedSuites failed")
        println("Tests:  $passedTests passed, $failedTests failed")
        println()
    }
}
