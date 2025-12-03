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

            if (cmd.subCommands != null) {
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
        // Print flow result line
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

        printSummary()
    }

    private fun formatDuration(ms: Long): String = when {
        ms < 1000 -> "${ms}ms"
        ms < 60000 -> String.format("%.1fs", ms / 1000.0)
        else -> "${ms / 60000}m ${(ms % 60000) / 1000}s"
    }

    private fun printSummary() {
        if (flowStats.isEmpty()) return

        val totalStats = FlowStats()
        flowStats.values.forEach { totalStats.add(it) }

        println()
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

        // Always print table for consistency
        printSummaryTable()
    }

    private fun printSummaryTable() {
        println()
        println("=".repeat(90))
        println(String.format("  %-38s %6s %6s %6s %6s %6s", "Flow", "Status", "Steps", "Pass", "Fail", "Skip"))
        println("-".repeat(90))

        flowStats.forEach { (name, stats) ->
            val shortName = if (name.length > 36) "...${name.takeLast(33)}" else name
            val passed = stats.failed == 0
            val statusSymbol = if (passed) "\u2713" else "\u2715"
            val statusColor = if (passed) Ansi.Color.GREEN else Ansi.Color.RED
            val totalSteps = stats.passed + stats.failed + stats.warned + stats.skipped

            print(String.format("  %-38s ", shortName))
            print(Ansi.ansi().fg(statusColor).a(String.format("%6s", statusSymbol)).reset())
            println(String.format(" %6d %6d %6d %6d", totalSteps, stats.passed, stats.failed, stats.skipped))
        }

        println("-".repeat(90))
        val total = FlowStats().also { t -> flowStats.values.forEach { t.add(it) } }
        val totalSteps = total.passed + total.failed + total.warned + total.skipped
        println(String.format("  %-38s %6s %6d %6d %6d %6d", "Total", "", totalSteps, total.passed, total.failed, total.skipped))
        println("=".repeat(90))
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
}
