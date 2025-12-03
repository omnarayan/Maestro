package maestro.cli.report

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import maestro.cli.runner.CommandStatus
import maestro.device.Device
import maestro.orchestra.MaestroCommand
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Generates JSON report for test runs.
 * Creates reports in ./reports/YYYY-MM-DD_HHmmss/report.json by default.
 */
object JsonReportGenerator {

    private val mapper = jacksonObjectMapper()
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)
        .writerWithDefaultPrettyPrinter()

    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")
    private val isoFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    private var reportDir: Path? = null
    private var currentReport: ReportData? = null
    private var currentFlow: FlowData? = null
    private var commandStack = mutableListOf<CommandData>()
    private var startTime: ZonedDateTime? = null
    private var flowCounter = 0
    private var commandCounters = mutableListOf<Int>()

    fun init(reportDirPath: String? = null) {
        val baseDir = reportDirPath ?: "./reports"
        val timestamp = timestampFormatter.format(LocalDateTime.now())
        reportDir = Paths.get(baseDir, timestamp)
        Files.createDirectories(reportDir)

        startTime = ZonedDateTime.now()
        currentReport = ReportData(
            schemaVersion = "1.0.0",
            summary = SummaryData(),
            device = null,
            flows = mutableListOf()
        )
        currentFlow = null
        commandStack.clear()
        flowCounter = 0
        commandCounters.clear()
    }

    fun setDevice(device: Device?) {
        device?.let {
            val deviceName = when (it) {
                is Device.Connected -> it.instanceId
                is Device.AvailableForLaunch -> it.modelId
            }
            currentReport?.device = DeviceData(
                name = deviceName,
                platform = it.platform.toString().lowercase(),
                osVersion = "unknown" // OS version not available in Device
            )
        }
    }

    fun startFlow(name: String, appId: String?, tags: List<String>?) {
        flowCounter++
        commandCounters.clear()
        commandCounters.add(0) // Start with depth 0

        currentFlow = FlowData(
            id = "flow_%03d".format(flowCounter),
            name = name,
            appId = appId,
            tags = tags,
            status = "passed",
            startTime = isoFormatter.format(ZonedDateTime.now()),
            endTime = null,
            durationMs = 0,
            commands = mutableListOf()
        )
        commandStack.clear()
    }

    fun endFlow(success: Boolean, errorMessage: String? = null) {
        currentFlow?.let { flow ->
            flow.status = if (success) "passed" else "failed"
            flow.endTime = isoFormatter.format(ZonedDateTime.now())
            flow.error = errorMessage

            // Calculate duration
            val start = ZonedDateTime.parse(flow.startTime, isoFormatter)
            val end = ZonedDateTime.parse(flow.endTime, isoFormatter)
            flow.durationMs = java.time.Duration.between(start, end).toMillis()

            (currentReport?.flows as MutableList).add(flow)
        }
        currentFlow = null
        commandStack.clear()
    }

    fun startCommand(command: MaestroCommand) {
        val type = getCommandType(command)
        val description = command.description()
        startCommandInternal(type, description)
    }

    private fun getCommandType(command: MaestroCommand): String {
        return when {
            command.launchAppCommand != null -> "launchApp"
            command.tapOnElement != null -> "tapOn"
            command.tapOnPointV2Command != null -> "tapOnPoint"
            command.assertConditionCommand != null -> "assertCondition"
            @Suppress("DEPRECATION")
            command.assertCommand != null -> "assert"
            command.inputTextCommand != null -> "inputText"
            command.runFlowCommand != null -> "runFlow"
            command.scrollCommand != null -> "scroll"
            command.swipeCommand != null -> "swipe"
            command.backPressCommand != null -> "backPress"
            command.waitForAnimationToEndCommand != null -> "waitForAnimationToEnd"
            command.takeScreenshotCommand != null -> "takeScreenshot"
            command.clearStateCommand != null -> "clearState"
            command.stopAppCommand != null -> "stopApp"
            command.killAppCommand != null -> "killApp"
            command.openLinkCommand != null -> "openLink"
            command.pressKeyCommand != null -> "pressKey"
            command.eraseTextCommand != null -> "eraseText"
            command.hideKeyboardCommand != null -> "hideKeyboard"
            command.setLocationCommand != null -> "setLocation"
            command.repeatCommand != null -> "repeat"
            command.runScriptCommand != null -> "runScript"
            command.evalScriptCommand != null -> "evalScript"
            command.defineVariablesCommand != null -> "defineVariables"
            command.scrollUntilVisible != null -> "scrollUntilVisible"
            command.copyTextCommand != null -> "copyText"
            command.pasteTextCommand != null -> "pasteText"
            command.setOrientationCommand != null -> "setOrientation"
            command.addMediaCommand != null -> "addMedia"
            command.setAirplaneModeCommand != null -> "setAirplaneMode"
            command.retryCommand != null -> "retry"
            else -> "unknown"
        }
    }

    private fun generateCommandId(): String {
        // Increment counter at current depth
        val depth = commandStack.size
        while (commandCounters.size <= depth) {
            commandCounters.add(0)
        }
        commandCounters[depth] = commandCounters[depth] + 1

        // Build ID like cmd_001, cmd_001_001, cmd_001_001_001
        val parts = mutableListOf<String>()
        for (i in 0..depth) {
            parts.add("%03d".format(commandCounters[i]))
        }
        return "cmd_" + parts.joinToString("_")
    }

    private fun getCommandIndex(): Int {
        val depth = commandStack.size
        return if (depth < commandCounters.size) commandCounters[depth] - 1 else 0
    }

    private fun startCommandInternal(type: String, description: String) {
        val commandId = generateCommandId()
        val index = getCommandIndex()

        val command = CommandData(
            id = commandId,
            index = index,
            type = type,
            description = description,
            status = "passed",
            startTime = isoFormatter.format(ZonedDateTime.now()),
            endTime = null,
            durationMs = 0,
            attempts = 1,
            commands = null
        )

        if (commandStack.isEmpty()) {
            currentFlow?.commands?.add(command)
        } else {
            val parent = commandStack.last()
            if (parent.commands == null) {
                parent.commands = mutableListOf()
            }
            parent.commands?.add(command)
        }
        commandStack.add(command)

        // Reset counters for nested commands
        val nextDepth = commandStack.size
        while (commandCounters.size <= nextDepth) {
            commandCounters.add(0)
        }
        commandCounters[nextDepth] = 0
    }

    fun endCommand(status: CommandStatus, errorMessage: String? = null, screenshot: String? = null) {
        if (commandStack.isEmpty()) return

        val command = commandStack.removeLast()
        command.status = when (status) {
            CommandStatus.COMPLETED -> "passed"
            CommandStatus.WARNED -> "passed"
            CommandStatus.FAILED -> "failed"
            CommandStatus.SKIPPED -> "skipped"
            else -> "passed"
        }
        command.endTime = isoFormatter.format(ZonedDateTime.now())
        command.error = errorMessage
        command.screenshot = screenshot

        // Calculate duration
        val start = ZonedDateTime.parse(command.startTime, isoFormatter)
        val end = ZonedDateTime.parse(command.endTime, isoFormatter)
        command.durationMs = java.time.Duration.between(start, end).toMillis()

        // Mark flow as failed if command failed
        if (command.status == "failed") {
            currentFlow?.status = "failed"
        }
    }

    fun setSubFlow(name: String) {
        if (commandStack.isNotEmpty()) {
            commandStack.last().subFlow = SubFlowData(name = name)
        }
    }

    fun save(): Path? {
        val report = currentReport ?: return null
        val dir = reportDir ?: return null

        // Update summary
        val flows = report.flows
        val endTime = ZonedDateTime.now()

        report.summary = SummaryData(
            totalFlows = flows.size,
            passedFlows = flows.count { it.status == "passed" },
            failedFlows = flows.count { it.status == "failed" },
            skippedFlows = flows.count { it.status == "skipped" },
            totalCommands = countFlowCommands(flows),
            passedCommands = countFlowCommands(flows, "passed"),
            failedCommands = countFlowCommands(flows, "failed"),
            skippedCommands = countFlowCommands(flows, "skipped"),
            totalDurationMs = startTime?.let { java.time.Duration.between(it, endTime).toMillis() } ?: 0,
            startTime = startTime?.let { isoFormatter.format(it) },
            endTime = isoFormatter.format(endTime)
        )

        val reportFile = dir.resolve("report.json").toFile()
        mapper.writeValue(reportFile, report)

        // Generate JUnit XML report from JSON
        generateJUnitReport(report, dir)

        return dir
    }

    private fun generateJUnitReport(report: ReportData, dir: Path) {
        val s = report.summary
        val xml = buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<testsuites name="Maestro Tests" tests="${s.totalFlows}" failures="${s.failedFlows}" errors="0" skipped="${s.skippedFlows}" time="${s.totalDurationMs / 1000.0}">""")

            for (flow in report.flows) {
                val timeSec = flow.durationMs / 1000.0
                val name = flow.name.escapeXml()
                appendLine("""  <testsuite name="$name" tests="${countCommands(flow.commands)}" failures="${countCommands(flow.commands, "failed")}" errors="0" skipped="${countCommands(flow.commands, "skipped")}" time="$timeSec">""")
                appendLine("""    <testcase name="$name" classname="maestro.flows" time="$timeSec">""")
                when (flow.status) {
                    "failed" -> appendLine("""      <failure message="${(flow.error ?: "Test failed").escapeXml()}">${(flow.error ?: "").escapeXml()}</failure>""")
                    "skipped" -> appendLine("""      <skipped/>""")
                }
                appendLine("""    </testcase>""")
                appendLine("""  </testsuite>""")
            }
            appendLine("""</testsuites>""")
        }
        dir.resolve("junit.xml").toFile().writeText(xml)
    }

    private fun String.escapeXml() = this
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    fun getReportDir(): Path? = reportDir

    private fun countCommands(commands: List<CommandData>?, status: String? = null): Int {
        if (commands == null) return 0
        return commands.sumOf { cmd ->
            val self = if (status == null || cmd.status == status) 1 else 0
            self + countCommands(cmd.commands, status)
        }
    }

    private fun countFlowCommands(flows: List<FlowData>, status: String? = null): Int =
        flows.sumOf { countCommands(it.commands, status) }
}

// Data classes for JSON serialization
data class ReportData(
    val schemaVersion: String,
    var summary: SummaryData,
    var device: DeviceData?,
    val flows: MutableList<FlowData>
)

data class SummaryData(
    val totalFlows: Int = 0,
    val passedFlows: Int = 0,
    val failedFlows: Int = 0,
    val skippedFlows: Int = 0,
    val totalCommands: Int = 0,
    val passedCommands: Int = 0,
    val failedCommands: Int = 0,
    val skippedCommands: Int = 0,
    val totalDurationMs: Long = 0,
    val startTime: String? = null,
    val endTime: String? = null
)

data class DeviceData(
    val name: String,
    val platform: String,
    val osVersion: String
)

data class FlowData(
    val id: String,
    val name: String,
    val appId: String?,
    val tags: List<String>?,
    var status: String,
    val startTime: String,
    var endTime: String?,
    var durationMs: Long,
    var error: String? = null,
    val commands: MutableList<CommandData>
)

data class CommandData(
    val id: String,
    val index: Int,
    val type: String,
    val description: String,
    var status: String,
    val startTime: String,
    var endTime: String?,
    var durationMs: Long,
    var attempts: Int = 1,
    var error: String? = null,
    var screenshot: String? = null,
    var subFlow: SubFlowData? = null,
    var commands: MutableList<CommandData>? = null
)

data class SubFlowData(
    val name: String
)
