package maestro.cli.runner.resultview

import maestro.cli.runner.CommandState
import maestro.cli.runner.CommandStatus
import org.fusesource.jansi.Ansi

/**
 * Mocha-style console reporter - prints steps as they complete (append-only).
 */
class MochaResultView : ResultView {

    private var lastFlowName: String? = null
    private var printedPaths = mutableSetOf<String>()
    private var flowStats = mutableMapOf<String, FlowStats>()
    private var currentFlowStats: FlowStats? = null
    private var flowStartTime: Long = 0
    private var flowDurations = mutableMapOf<String, Long>()

    // Suite/test tracking
    private var suiteStats = mutableMapOf<String, SuiteStats>()
    private var currentSuiteName: String? = null
    private var currentTestName: String? = null
    private var hasSuiteStructure = false

    override fun setState(state: UiState) {
        when (state) {
            is UiState.Running -> renderRunning(state)
            is UiState.Error -> renderError(state)
        }
    }

    private fun renderError(state: UiState.Error) {
        println(Ansi.ansi().fgRed().a(state.message).reset())
    }

    private fun renderRunning(state: UiState.Running) {
        // Print flow header if changed
        if (state.flowName != lastFlowName) {
            if (lastFlowName == null) {
                println() // blank lines at start
                println()
            } else {
                println() // blank line between flows
            }
            println(Ansi.ansi().bold().a("  ${state.flowName}").reset())
            lastFlowName = state.flowName
            printedPaths.clear()
            currentFlowStats = FlowStats()
            flowStats[state.flowName] = currentFlowStats!!
            flowStartTime = System.currentTimeMillis()
            currentSuiteName = null
            currentTestName = null
        }

        // Process commands recursively
        processCommands(state.onFlowStartCommands, 2, "onStart")
        processCommands(state.commands, 2, "main")
        processCommands(state.onFlowCompleteCommands, 2, "onComplete")
    }

    private fun processCommands(commands: List<CommandState>, indent: Int, pathPrefix: String) {
        commands.forEachIndexed { index, cmd ->
            val path = "$pathPrefix:$index"

            // Skip invisible commands (like DefineVariablesCommand)
            if (cmd.command.asCommand()?.visible() == false) return@forEachIndexed

            // Check for suite/test commands
            val describeCmd = cmd.command.describeCommand
            val testCaseCmd = cmd.command.testCaseCommand

            if (describeCmd != null) {
                // Suite command - print as section header
                val headerPath = "$path:suite"
                if (headerPath !in printedPaths && cmd.status != CommandStatus.PENDING) {
                    hasSuiteStructure = true
                    currentSuiteName = describeCmd.description
                    println()
                    println(Ansi.ansi().bold().a("${spaces(indent)}${describeCmd.description}").reset())
                    printedPaths.add(headerPath)

                    // Initialize suite stats
                    if (currentSuiteName !in suiteStats) {
                        suiteStats[currentSuiteName!!] = SuiteStats(currentSuiteName!!)
                    }
                }
            } else if (testCaseCmd != null) {
                // Test command - print test header and process steps
                val headerPath = "$path:test"
                if (headerPath !in printedPaths && cmd.status != CommandStatus.PENDING) {
                    currentTestName = testCaseCmd.testName
                    printedPaths.add(headerPath)
                }

                // Process test steps
                if (cmd.subCommands != null) {
                    processCommands(cmd.subCommands, indent + 2, "$path:sub")
                }

                // Print test result when completed
                val resultPath = "$path:result"
                if (resultPath !in printedPaths && cmd.status in listOf(CommandStatus.COMPLETED, CommandStatus.FAILED, CommandStatus.SKIPPED)) {
                    val symbol = getSymbol(cmd.status)
                    val color = getColor(cmd.status)
                    println(Ansi.ansi().a(spaces(indent + 2)).fg(color).a(symbol).reset().a(" ${testCaseCmd.testName}"))
                    printedPaths.add(resultPath)

                    // Record in suite stats
                    currentSuiteName?.let { suiteName ->
                        suiteStats[suiteName]?.recordTest(testCaseCmd.testName, cmd.status)
                    }
                }
            } else if (cmd.subCommands != null) {
                // Subflow: print header when RUNNING or completed, then process children
                val headerPath = "$path:header"
                if (headerPath !in printedPaths && cmd.status != CommandStatus.PENDING) {
                    println("${spaces(indent)}${cmd.command.description()}")
                    printedPaths.add(headerPath)
                }

                // Process subflow children
                cmd.subOnStartCommands?.let { processCommands(it, indent + 2, "$path:subOnStart") }
                processCommands(cmd.subCommands, indent + 2, "$path:sub")
                cmd.subOnCompleteCommands?.let { processCommands(it, indent + 2, "$path:subOnComplete") }
            } else {
                // Regular command: print when completed (not PENDING/RUNNING)
                if (path !in printedPaths && cmd.status !in listOf(CommandStatus.PENDING, CommandStatus.RUNNING)) {
                    printStep(cmd, indent)
                    printedPaths.add(path)
                    currentFlowStats?.record(cmd.status)
                }
            }
        }
    }

    private fun printStep(cmd: CommandState, indent: Int) {
        val symbol = getSymbol(cmd.status)
        val color = getColor(cmd.status)
        val desc = cmd.command.description()

        val line = Ansi.ansi()
            .a(spaces(indent))
            .fg(color)
            .a(symbol)
            .reset()
            .a(" $desc")

        println(line)
    }

    private fun getSymbol(status: CommandStatus): String = when (status) {
        CommandStatus.COMPLETED -> "\u2713" // ✓
        CommandStatus.FAILED -> "\u2715"    // ✕
        CommandStatus.WARNED -> "\u26A0"    // ⚠
        CommandStatus.SKIPPED -> "\u25CB"   // ○
        CommandStatus.PENDING -> "\u00B7"   // ·
        CommandStatus.RUNNING -> "\u25CB"   // ○
    }

    private fun getColor(status: CommandStatus): Ansi.Color = when (status) {
        CommandStatus.COMPLETED -> Ansi.Color.GREEN
        CommandStatus.FAILED -> Ansi.Color.RED
        CommandStatus.WARNED -> Ansi.Color.YELLOW
        CommandStatus.SKIPPED -> Ansi.Color.CYAN
        CommandStatus.PENDING -> Ansi.Color.DEFAULT
        CommandStatus.RUNNING -> Ansi.Color.CYAN
    }

    private fun spaces(count: Int): String = " ".repeat(count)

    override fun close(passed: Boolean, duration: Long?, errorMessage: String?) {
        // Track duration
        lastFlowName?.let { flowName ->
            flowDurations[flowName] = duration ?: (System.currentTimeMillis() - flowStartTime)
        }

        // Print flow result line (only if no suite structure - otherwise suites are the summary)
        if (!hasSuiteStructure) {
            lastFlowName?.let { flowName ->
                val symbol = if (passed) "\u2713" else "\u2715"
                val color = if (passed) Ansi.Color.GREEN else Ansi.Color.RED
                val durationStr = duration?.let { formatDuration(it) } ?: ""
                val errorStr = if (!passed && errorMessage != null) " ($errorMessage)" else ""

                println(Ansi.ansi()
                    .fg(color)
                    .a("  $symbol $flowName $durationStr$errorStr")
                    .reset())
            }
        }

        printSummary()
    }

    private fun formatDuration(ms: Long): String = when {
        ms < 1000 -> "${ms}ms"
        ms < 60000 -> String.format("%.1fs", ms / 1000.0)
        else -> "${ms / 60000}m ${(ms % 60000) / 1000}s"
    }

    private fun printSummary() {
        if (flowStats.isEmpty() && suiteStats.isEmpty()) return

        println()

        if (hasSuiteStructure && suiteStats.isNotEmpty()) {
            // Print hierarchical Test Summary first
            printTestSummary()

            // Suite/test summary
            val totalTests = suiteStats.values.sumOf { it.totalTests }
            val passedTests = suiteStats.values.sumOf { it.passedTests }
            val failedTests = suiteStats.values.sumOf { it.failedTests }
            val skippedTests = suiteStats.values.sumOf { it.skippedTests }

            println(Ansi.ansi().fgGreen().a("  $passedTests passing").reset())
            if (failedTests > 0) {
                println(Ansi.ansi().fgRed().a("  $failedTests failing").reset())
            }
            if (skippedTests > 0) {
                println(Ansi.ansi().fgCyan().a("  $skippedTests skipped").reset())
            }

            println()

            // Suite summary
            val suitesPassed = suiteStats.values.count { it.failedTests == 0 }
            val allPassed = suitesPassed == suiteStats.size
            println(Ansi.ansi().fg(if (allPassed) Ansi.Color.GREEN else Ansi.Color.RED)
                .a("  $suitesPassed/${suiteStats.size} Suites Passed, $passedTests/$totalTests Tests Passed").reset())

            printSuiteTable()
        } else {
            // Flow-based summary
            val totalStats = FlowStats()
            flowStats.values.forEach { totalStats.add(it) }

            println(Ansi.ansi().fgGreen().a("  ${totalStats.passed} passing").reset())
            if (totalStats.failed > 0) {
                println(Ansi.ansi().fgRed().a("  ${totalStats.failed} failing").reset())
            }
            if (totalStats.warned > 0) {
                println(Ansi.ansi().fgYellow().a("  ${totalStats.warned} warnings").reset())
            }
            if (totalStats.skipped > 0) {
                println(Ansi.ansi().fgCyan().a("  ${totalStats.skipped} skipped").reset())
            }

            println()

            // Flow summary
            val flowsPassed = flowStats.values.count { it.failed == 0 }
            val allPassed = flowsPassed == flowStats.size
            println(Ansi.ansi().fg(if (allPassed) Ansi.Color.GREEN else Ansi.Color.RED)
                .a("  $flowsPassed/${flowStats.size} Flows Passed").reset())

            printSummaryTable()
        }
    }

    private fun printTestSummary() {
        val passedSuites = suiteStats.values.count { it.failedTests == 0 }
        val failedSuites = suiteStats.values.count { it.failedTests > 0 }
        val totalTests = suiteStats.values.sumOf { it.totalTests }
        val passedTests = suiteStats.values.sumOf { it.passedTests }
        val failedTests = suiteStats.values.sumOf { it.failedTests }
        val skippedTests = suiteStats.values.sumOf { it.skippedTests }

        println("===== Test Summary =====")
        println()

        // Suites and tests with checkboxes
        for ((_, suite) in suiteStats) {
            val suiteIcon = if (suite.failedTests == 0) "✅" else "❌"
            println("$suiteIcon ${suite.name}")

            for (test in suite.tests) {
                val testIcon = when {
                    test.skipped -> "⏭️"
                    test.passed -> "✅"
                    else -> "❌"
                }
                println("  $testIcon ${test.name}")
            }
        }

        println()
        println("========================")
        println("Suites: $passedSuites passed, $failedSuites failed")
        val skippedStr = if (skippedTests > 0) ", $skippedTests skipped" else ""
        println("Tests:  $passedTests passed, $failedTests failed$skippedStr")
        println()
    }

    private fun printSummaryTable() {
        println()
        println("=".repeat(100))
        println(String.format("  %-38s %6s %6s %6s %6s %6s %10s", "Flow", "Status", "Steps", "Pass", "Fail", "Skip", "Duration"))
        println("-".repeat(100))

        flowStats.forEach { (name, stats) ->
            val shortName = if (name.length > 36) "...${name.takeLast(33)}" else name
            val passed = stats.failed == 0
            val statusSymbol = if (passed) "\u2713" else "\u2715"
            val statusColor = if (passed) Ansi.Color.GREEN else Ansi.Color.RED
            val totalSteps = stats.passed + stats.failed + stats.warned + stats.skipped
            val duration = flowDurations[name]?.let { formatDuration(it) } ?: ""

            print(String.format("  %-38s ", shortName))
            print(Ansi.ansi().fg(statusColor).a(String.format("%6s", statusSymbol)).reset())
            println(String.format(" %6d %6d %6d %6d %10s", totalSteps, stats.passed, stats.failed, stats.skipped, duration))
        }

        println("-".repeat(100))
        val total = FlowStats().also { t -> flowStats.values.forEach { t.add(it) } }
        val totalSteps = total.passed + total.failed + total.warned + total.skipped
        val totalDuration = flowDurations.values.sum().let { formatDuration(it) }
        println(String.format("  %-38s %6s %6d %6d %6d %6d %10s", "Total", "", totalSteps, total.passed, total.failed, total.skipped, totalDuration))
        println("=".repeat(100))
        println()
        println()
    }

    private fun printSuiteTable() {
        println()
        println("=".repeat(100))
        println(String.format("  %-38s %6s %6s %6s %6s %6s %10s", "Suite", "Status", "Tests", "Pass", "Fail", "Skip", "Duration"))
        println("-".repeat(100))

        suiteStats.forEach { (name, stats) ->
            val shortName = if (name.length > 36) "...${name.takeLast(33)}" else name
            val passed = stats.failedTests == 0
            val statusSymbol = if (passed) "\u2713" else "\u2715"
            val statusColor = if (passed) Ansi.Color.GREEN else Ansi.Color.RED

            print(String.format("  %-38s ", shortName))
            print(Ansi.ansi().fg(statusColor).a(String.format("%6s", statusSymbol)).reset())
            println(String.format(" %6d %6d %6d %6d %10s", stats.totalTests, stats.passedTests, stats.failedTests, stats.skippedTests, ""))
        }

        println("-".repeat(100))
        val totalTests = suiteStats.values.sumOf { it.totalTests }
        val passedTests = suiteStats.values.sumOf { it.passedTests }
        val failedTests = suiteStats.values.sumOf { it.failedTests }
        val skippedTests = suiteStats.values.sumOf { it.skippedTests }
        val totalDuration = flowDurations.values.sum().let { formatDuration(it) }
        println(String.format("  %-38s %6s %6d %6d %6d %6d %10s", "Total", "", totalTests, passedTests, failedTests, skippedTests, totalDuration))
        println("=".repeat(100))
        println()
        println()
    }

    private class FlowStats {
        var passed = 0
        var failed = 0
        var warned = 0
        var skipped = 0

        fun record(status: CommandStatus) {
            when (status) {
                CommandStatus.COMPLETED -> passed++
                CommandStatus.FAILED -> failed++
                CommandStatus.WARNED -> warned++
                CommandStatus.SKIPPED -> skipped++
                else -> {}
            }
        }

        fun add(other: FlowStats) {
            passed += other.passed
            failed += other.failed
            warned += other.warned
            skipped += other.skipped
        }
    }

    private data class TestResult(val name: String, val passed: Boolean, val skipped: Boolean = false)

    private class SuiteStats(val name: String) {
        val tests = mutableListOf<TestResult>()
        var passedTests = 0
        var failedTests = 0
        var skippedTests = 0

        val totalTests: Int get() = passedTests + failedTests + skippedTests

        fun recordTest(status: CommandStatus) {
            when (status) {
                CommandStatus.COMPLETED -> passedTests++
                CommandStatus.FAILED -> failedTests++
                CommandStatus.SKIPPED -> skippedTests++
                else -> {}
            }
        }

        fun recordTest(testName: String, status: CommandStatus) {
            val passed = status == CommandStatus.COMPLETED
            val skipped = status == CommandStatus.SKIPPED
            tests.add(TestResult(testName, passed, skipped))
            recordTest(status)
        }
    }
}
