package maestro.cli.runner

import maestro.orchestra.Command
import maestro.orchestra.CompositeCommand
import org.fusesource.jansi.Ansi
import kotlin.time.Duration

/**
 * Simple mocha-style step reporter for multi-flow test suite runs.
 * Prints steps as they complete (append-only).
 */
class MochaStepReporter {

    private var currentFlowName: String? = null
    private var indentLevel = 0
    private var currentFlowStats: FlowStats? = null
    private val allFlowStats = mutableListOf<FlowStats>()

    fun onFlowStart(flowName: String) {
        if (currentFlowName == null) {
            println() // blank lines at start
            println()
        } else {
            println() // blank line between flows
        }
        println(Ansi.ansi().bold().a("  $flowName").reset())
        currentFlowName = flowName
        indentLevel = 1
        currentFlowStats = FlowStats(flowName)
    }

    fun onFlowComplete(flowName: String, passed: Boolean, duration: Duration?, errorMessage: String?) {
        currentFlowStats?.let {
            it.passed = passed
            it.duration = duration
            allFlowStats.add(it)
        }

        val errorStr = if (!passed && errorMessage != null) " ($errorMessage)" else ""
        println(Ansi.ansi()
            .fg(getPassFailColor(passed))
            .a("  ${getPassFailSymbol(passed)} $flowName ${duration?.let { formatDuration(it) } ?: ""}$errorStr")
            .reset())

        currentFlowStats = null
    }

    fun onCommandStart(command: Command) {
        if (command is CompositeCommand && command.visible()) {
            println("${indent()}${command.description()}")
            indentLevel++
        }
    }

    fun onCommandComplete(command: Command) = handleCommand(command, CommandStatus.COMPLETED)
    fun onCommandFailed(command: Command) = handleCommand(command, CommandStatus.FAILED)
    fun onCommandSkipped(command: Command) = handleCommand(command, CommandStatus.SKIPPED)
    fun onCommandWarned(command: Command) = handleCommand(command, CommandStatus.WARNED)

    private fun handleCommand(command: Command, status: CommandStatus) {
        if (command is CompositeCommand) {
            if (command.visible()) indentLevel = maxOf(0, indentLevel - 1)
            return
        }
        if (!command.visible()) return
        printStep(command, status)
        currentFlowStats?.record(status)
    }

    fun printSummary() {
        if (allFlowStats.isEmpty()) return

        val totals = calculateTotals()
        val flowsPassed = allFlowStats.count { it.passed }

        println()
        println()

        // Step summary
        println(Ansi.ansi().fgGreen().a("  ${totals.passed} steps passing${totals.duration?.let { " (${formatDuration(it)})" } ?: ""}").reset())
        if (totals.failed > 0) println(Ansi.ansi().fgRed().a("  ${totals.failed} steps failing").reset())
        if (totals.warned > 0) println(Ansi.ansi().fgYellow().a("  ${totals.warned} optional failures").reset())
        if (totals.skipped > 0) println(Ansi.ansi().fgCyan().a("  ${totals.skipped} steps skipped").reset())

        println()

        // Flow summary
        val allPassed = flowsPassed == allFlowStats.size
        println(Ansi.ansi().fg(getPassFailColor(allPassed)).a("  $flowsPassed/${allFlowStats.size} Flows Passed").reset())

        if (allFlowStats.size > 1) printSummaryTable(totals)
    }

    private fun printSummaryTable(totals: Totals) {
        println()
        println("=".repeat(90))
        println(String.format("  %-38s %6s %6s %6s %6s %6s %10s", "Flow", "Status", "Steps", "Pass", "Fail", "Skip", "Duration"))
        println("-".repeat(90))

        allFlowStats.forEach { stats ->
            val shortName = if (stats.flowName.length > 36) "...${stats.flowName.takeLast(33)}" else stats.flowName
            val totalSteps = stats.totalSteps()
            val durationStr = stats.duration?.let { formatDuration(it) } ?: "-"

            print(String.format("  %-38s ", shortName))
            print(Ansi.ansi().fg(getPassFailColor(stats.passed)).a(String.format("%6s", getPassFailSymbol(stats.passed))).reset())
            println(String.format(" %6d %6d %6d %6d %10s", totalSteps, stats.passedCount, stats.failedCount, stats.skippedCount, durationStr))
        }

        println("-".repeat(90))
        println(String.format("  %-38s %6s %6d %6d %6d %6d %10s", "Total", "",
            totals.steps, totals.passed, totals.failed, totals.skipped,
            totals.duration?.let { formatDuration(it) } ?: "-"))
        println("=".repeat(90))
        println()
        println()
    }

    private fun calculateTotals() = Totals(
        passed = allFlowStats.sumOf { it.passedCount },
        failed = allFlowStats.sumOf { it.failedCount },
        warned = allFlowStats.sumOf { it.warnedCount },
        skipped = allFlowStats.sumOf { it.skippedCount },
        steps = allFlowStats.sumOf { it.totalSteps() },
        duration = allFlowStats.mapNotNull { it.duration }.reduceOrNull { a, b -> a + b }
    )

    private fun printStep(command: Command, status: CommandStatus) {
        println(Ansi.ansi()
            .a(indent())
            .fg(getColor(status))
            .a(getSymbol(status))
            .reset()
            .a(" ${command.description()}"))
    }

    private fun indent(): String = "  " + "  ".repeat(indentLevel)

    private fun formatDuration(duration: Duration): String {
        val ms = duration.inWholeMilliseconds
        return when {
            ms < 1000 -> "${ms}ms"
            ms < 60000 -> String.format("%.1fs", ms / 1000.0)
            else -> "${ms / 60000}m ${(ms % 60000) / 1000}s"
        }
    }

    private fun getPassFailSymbol(passed: Boolean) = if (passed) "\u2713" else "\u2715"
    private fun getPassFailColor(passed: Boolean) = if (passed) Ansi.Color.GREEN else Ansi.Color.RED

    private fun getSymbol(status: CommandStatus): String = when (status) {
        CommandStatus.COMPLETED -> "\u2713"
        CommandStatus.FAILED -> "\u2715"
        CommandStatus.WARNED -> "\u26A0"
        CommandStatus.SKIPPED -> "\u25CB"
        else -> "\u00B7"
    }

    private fun getColor(status: CommandStatus): Ansi.Color = when (status) {
        CommandStatus.COMPLETED -> Ansi.Color.GREEN
        CommandStatus.FAILED -> Ansi.Color.RED
        CommandStatus.WARNED -> Ansi.Color.YELLOW
        CommandStatus.SKIPPED -> Ansi.Color.CYAN
        else -> Ansi.Color.DEFAULT
    }

    private data class Totals(
        val passed: Int,
        val failed: Int,
        val warned: Int,
        val skipped: Int,
        val steps: Int,
        val duration: Duration?
    )

    private class FlowStats(val flowName: String) {
        var passed = true
        var duration: Duration? = null
        var passedCount = 0
        var failedCount = 0
        var warnedCount = 0
        var skippedCount = 0

        fun totalSteps() = passedCount + failedCount + warnedCount + skippedCount

        fun record(status: CommandStatus) {
            when (status) {
                CommandStatus.COMPLETED -> passedCount++
                CommandStatus.FAILED -> failedCount++
                CommandStatus.WARNED -> warnedCount++
                CommandStatus.SKIPPED -> skippedCount++
                else -> {}
            }
        }
    }
}
