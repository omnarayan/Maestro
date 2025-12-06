package maestro.cli.report

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import maestro.cli.runner.CommandStatus
import maestro.device.Device
import maestro.orchestra.MaestroCommand
import java.io.File
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

    private fun now() = isoFormatter.format(ZonedDateTime.now())
    private fun durationMs(start: String, end: String) = java.time.Duration.between(
        ZonedDateTime.parse(start, isoFormatter),
        ZonedDateTime.parse(end, isoFormatter)
    ).toMillis()

    private var reportDir: Path? = null
    private var screenshotsDir: Path? = null
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

        // Create screenshots folder
        screenshotsDir = reportDir?.resolve("screenshots")
        Files.createDirectories(screenshotsDir)

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
            startTime = now(),
            endTime = null,
            durationMs = 0,
            commands = mutableListOf()
        )
        commandStack.clear()
    }

    fun endFlow(success: Boolean, errorMessage: String? = null) {
        currentFlow?.let { flow ->
            flow.status = if (success) "passed" else "failed"
            flow.endTime = now()
            flow.error = errorMessage
            flow.durationMs = durationMs(flow.startTime, flow.endTime!!)

            // Build suite/test structure from commands
            flow.suites = buildSuiteStructure(flow.commands, flow.name)

            (currentReport?.flows as MutableList).add(flow)
        }
        currentFlow = null
        commandStack.clear()
    }

    /**
     * Builds hierarchical suite/test structure from command list.
     * Recursively searches through nested commands (runFlow) to find suite/test.
     * Returns null if no suite/test commands are found.
     * Each test includes the file name for tracking.
     */
    private fun buildSuiteStructure(commands: List<CommandData>, flowName: String): MutableList<SuiteData>? {
        val suites = mutableListOf<SuiteData>()
        var currentSuite: SuiteData? = null
        var hasSuiteOrTest = false

        // Recursively process commands to find suite/test
        fun processCommands(cmds: List<CommandData>, currentFileName: String) {
            for (cmd in cmds) {
                when (cmd.type) {
                    "suite" -> {
                        hasSuiteOrTest = true
                        // Extract suite name from description (format: "Suite: Name")
                        val suiteName = cmd.description.removePrefix("Suite: ")
                        currentSuite = SuiteData(
                            suite = suiteName,
                            status = cmd.status,
                            durationMs = cmd.durationMs
                        )
                        suites.add(currentSuite!!)
                    }
                    "test" -> {
                        hasSuiteOrTest = true
                        // Extract test name from description (format: "Test: Name")
                        val testName = cmd.description.removePrefix("Test: ")
                        // Find screenshot from test steps if any failed
                        val testScreenshot = cmd.commands?.lastOrNull { it.screenshot != null }?.screenshot
                        val testData = TestData(
                            test = testName,
                            file = currentFileName,
                            status = cmd.status,
                            durationMs = cmd.durationMs,
                            error = cmd.error,
                            screenshot = testScreenshot,
                            steps = cmd.commands?.toMutableList() ?: mutableListOf()
                        )

                        val suite = currentSuite
                        if (suite != null) {
                            suite.tests.add(testData)
                            // Update suite status if test failed
                            if (cmd.status == "failed") {
                                suite.status = "failed"
                            }
                        } else {
                            // Test without suite - create default suite
                            val defaultSuite = SuiteData(
                                suite = flowName,
                                status = cmd.status,
                                durationMs = cmd.durationMs
                            )
                            defaultSuite.tests.add(testData)
                            suites.add(defaultSuite)
                            currentSuite = defaultSuite
                        }
                    }
                    "runFlow" -> {
                        // Extract file name from runFlow description
                        // Format: "Run FlowName (path/to/file.yaml)" or "Run FlowName (path/to/file.yml)"
                        val fileMatch = Regex("\\(([^)]+\\.ya?ml)\\)").find(cmd.description)
                        val subFlowFile = fileMatch?.groupValues?.get(1)?.substringAfterLast("/") ?: currentFileName

                        // Recursively process nested commands
                        cmd.commands?.let { nestedCmds ->
                            processCommands(nestedCmds, subFlowFile)
                        }
                    }
                    else -> {
                        // For other composite commands, also check nested commands
                        cmd.commands?.let { nestedCmds ->
                            processCommands(nestedCmds, currentFileName)
                        }
                    }
                }
            }
        }

        processCommands(commands, flowName)
        return if (hasSuiteOrTest) suites else null
    }

    /**
     * Aggregates suites from all flows globally.
     * Suites with the same name are merged, tests track their source file.
     */
    private fun buildGlobalSuites(flows: List<FlowData>): MutableList<SuiteData>? {
        val globalSuiteMap = mutableMapOf<String, SuiteData>()
        var hasSuites = false

        for (flow in flows) {
            flow.suites?.forEach { flowSuite ->
                hasSuites = true
                val existing = globalSuiteMap[flowSuite.suite]
                if (existing != null) {
                    // Merge tests into existing suite
                    existing.tests.addAll(flowSuite.tests)
                    existing.durationMs += flowSuite.durationMs
                    // Update status if any test failed
                    if (flowSuite.status == "failed") {
                        existing.status = "failed"
                    }
                } else {
                    // Create new suite (copy to avoid modifying original)
                    globalSuiteMap[flowSuite.suite] = SuiteData(
                        suite = flowSuite.suite,
                        status = flowSuite.status,
                        durationMs = flowSuite.durationMs,
                        tests = flowSuite.tests.toMutableList()
                    )
                }
            }
        }

        return if (hasSuites) globalSuiteMap.values.toMutableList() else null
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
            command.describeCommand != null -> "suite"
            command.testCaseCommand != null -> "test"
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
            startTime = now(),
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
        command.endTime = now()
        command.error = errorMessage
        // Only set screenshot if provided (don't override if already set by saveScreenshot)
        if (screenshot != null) {
            command.screenshot = screenshot
        }
        command.durationMs = durationMs(command.startTime, command.endTime!!)

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

    /**
     * Save screenshot to report folder and return relative path.
     * Links it to current command if one is active.
     */
    fun saveScreenshot(sourceFile: File, status: CommandStatus): String? {
        val dir = screenshotsDir ?: return null
        if (!sourceFile.exists()) return null

        val filename = "screenshot-${System.currentTimeMillis()}-${status.name.lowercase()}.png"
        val destFile = dir.resolve(filename).toFile()

        try {
            sourceFile.copyTo(destFile, overwrite = true)
            val relativePath = "screenshots/$filename"

            // Link to current command
            if (commandStack.isNotEmpty()) {
                commandStack.last().screenshot = relativePath
            }

            return relativePath
        } catch (e: Exception) {
            return null
        }
    }

    fun save(): Path? {
        val report = currentReport ?: return null
        val dir = reportDir ?: return null

        try {
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

            // Build global suite aggregation across all flows
            report.suites = buildGlobalSuites(flows)

            val reportFile = dir.resolve("report.json").toFile()
            mapper.writeValue(reportFile, report)

            // Generate other report formats
            generateJUnitReport(report, dir)
            generateHtmlReport(report, dir)
            generateAllureResults(report, dir)

            return dir
        } catch (e: Exception) {
            System.err.println("Error saving report: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    fun printReportPaths() {
        val dir = reportDir ?: return
        val absPath = dir.toAbsolutePath().normalize()
        println()
        println()
        println("Reports:")
        println("  HTML:   $absPath/index.html")
        println("  JSON:   $absPath/report.json")
        println("  JUnit:  $absPath/junit-report.xml")
        println("  Allure: $absPath/allure-results")
        println()
        println("(by \u001B]8;;https://devicelab.dev\u0007\u001B[36mDeviceLab.dev\u001B[0m\u001B]8;;\u0007 - Turn Your Devices Into a Distributed Device Lab)")
        println()
        println()
    }

    private fun generateJUnitReport(report: ReportData, dir: Path) {
        val s = report.summary
        val d = report.device
        val suites = report.suites

        val xml = buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")

            // If we have suites, use suite/test structure
            if (!suites.isNullOrEmpty()) {
                val totalTests = suites.sumOf { it.tests.size }
                val failedTests = suites.sumOf { suite -> suite.tests.count { it.status == "failed" } }
                val skippedTests = suites.sumOf { suite -> suite.tests.count { it.status == "skipped" } }
                val totalTime = suites.sumOf { it.durationMs } / 1000.0

                appendLine("""<testsuites tests="$totalTests" failures="$failedTests" skipped="$skippedTests" errors="0" time="$totalTime">""")

                for (suite in suites) {
                    val suiteTests = suite.tests.size
                    val suiteFailed = suite.tests.count { it.status == "failed" }
                    val suiteSkipped = suite.tests.count { it.status == "skipped" }
                    val suiteTime = suite.durationMs / 1000.0
                    val suiteName = suite.suite.escapeXml()

                    appendLine("""  <testsuite name="$suiteName" tests="$suiteTests" failures="$suiteFailed" skipped="$suiteSkipped" errors="0" time="$suiteTime" timestamp="${s.startTime ?: ""}">""")
                    appendLine("""    <properties>""")
                    d?.let {
                        appendLine("""      <property name="device.name" value="${it.name.escapeXml()}"></property>""")
                        appendLine("""      <property name="device.platform" value="${it.platform.escapeXml()}"></property>""")
                        appendLine("""      <property name="device.osVersion" value="${it.osVersion.escapeXml()}"></property>""")
                    }
                    appendLine("""      <property name="framework" value="maestro"></property>""")
                    appendLine("""    </properties>""")

                    for (test in suite.tests) {
                        val testName = test.test.escapeXml()
                        val testFile = (test.file ?: "unknown").escapeXml()
                        val testTime = test.durationMs / 1000.0

                        append("""    <testcase name="$testName" classname="$testFile" time="$testTime">""")
                        when (test.status) {
                            "failed" -> appendLine("""<failure message="${(test.error ?: "Test failed").escapeXml()}">${(test.error ?: "").escapeXml()}</failure></testcase>""")
                            "skipped" -> appendLine("""<skipped/></testcase>""")
                            else -> appendLine("""</testcase>""")
                        }
                    }
                    appendLine("""  </testsuite>""")
                }
                appendLine("""</testsuites>""")
            } else {
                // Fallback to flow-based structure
                appendLine("""<testsuites tests="${s.totalFlows}" failures="${s.failedFlows}" skipped="${s.skippedFlows}" errors="0" time="${s.totalDurationMs / 1000.0}">""")
                appendLine("""  <testsuite name="Maestro Test Suite" tests="${s.totalFlows}" failures="${s.failedFlows}" skipped="${s.skippedFlows}" errors="0" time="${s.totalDurationMs / 1000.0}" timestamp="${s.startTime ?: ""}">""")
                appendLine("""    <properties>""")
                d?.let {
                    appendLine("""      <property name="device.name" value="${it.name.escapeXml()}"></property>""")
                    appendLine("""      <property name="device.platform" value="${it.platform.escapeXml()}"></property>""")
                    appendLine("""      <property name="device.osVersion" value="${it.osVersion.escapeXml()}"></property>""")
                }
                appendLine("""      <property name="framework" value="maestro"></property>""")
                appendLine("""    </properties>""")

                for (flow in report.flows) {
                    val timeSec = flow.durationMs / 1000.0
                    val name = flow.name.escapeXml()
                    append("""    <testcase name="$name" classname="$name" time="$timeSec">""")
                    when (flow.status) {
                        "failed" -> appendLine("""<failure message="${(flow.error ?: "Test failed").escapeXml()}">${(flow.error ?: "").escapeXml()}</failure></testcase>""")
                        "skipped" -> appendLine("""<skipped/></testcase>""")
                        else -> appendLine("""</testcase>""")
                    }
                }
                appendLine("""  </testsuite>""")
                appendLine("""</testsuites>""")
            }
        }
        dir.resolve("junit-report.xml").toFile().writeText(xml)
    }

    private fun generateHtmlReport(report: ReportData, dir: Path) {
        // Read JSON and embed into HTML template
        val jsonData = jacksonObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .writerWithDefaultPrettyPrinter()
            .writeValueAsString(report)

        val html = getHtmlTemplate().replace("const REPORT_DATA = {};", "const REPORT_DATA = $jsonData;")
        dir.resolve("index.html").toFile().writeText(html)
    }

    private fun getHtmlTemplate(): String {
        // Embedded HTML template with JavaScript rendering
        return """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Test Report</title>
    <style>
        :root {
            --bg-primary: #ffffff;
            --bg-secondary: #f8fafc;
            --border-light: #e2e8f0;
            --text-primary: rgb(17, 24, 39);
            --text-secondary: rgb(107, 114, 128);
            --text-muted: rgb(156, 163, 175);
            --accent: #06b6d4;
            --success: #22c55e;
            --failure: #ef4444;
        }
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body { font-family: -apple-system, BlinkMacSystemFont, sans-serif; background: var(--bg-secondary); color: var(--text-primary); line-height: 1.5; }
        .container { max-width: 1200px; margin: 0 auto; padding: 24px; }
        .header { background: var(--bg-primary); border: 1px solid var(--border-light); border-radius: 12px; padding: 24px; margin-bottom: 20px; }
        .header-top { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 20px; flex-wrap: wrap; gap: 16px; }
        .header h1 { font-size: 22px; font-weight: 700; margin-bottom: 8px; }
        .header-meta { font-size: 13px; color: var(--text-secondary); }
        .device-badge { display: flex; align-items: center; gap: 10px; padding: 10px 14px; background: var(--bg-secondary); border: 1px solid var(--border-light); border-radius: 8px; font-size: 13px; }
        .device-badge strong { display: block; color: var(--text-primary); }
        .device-badge span { color: var(--text-secondary); font-size: 12px; }
        .stats { display: flex; gap: 12px; flex-wrap: wrap; margin-bottom: 16px; }
        .stat { display: flex; align-items: baseline; gap: 8px; padding: 12px 16px; background: var(--bg-secondary); border-radius: 8px; min-width: 100px; }
        .stat-value { font-size: 26px; font-weight: 700; font-family: monospace; }
        .stat-label { font-size: 12px; color: var(--text-secondary); }
        .stat.passed .stat-value { color: var(--success); }
        .stat.failed .stat-value { color: var(--failure); }
        .stat.duration .stat-value { color: var(--accent); font-size: 20px; }
        .progress-row { display: flex; align-items: center; gap: 12px; padding-top: 16px; border-top: 1px solid var(--border-light); }
        .progress-label { font-size: 13px; color: var(--text-secondary); min-width: 70px; }
        .progress-bar { flex: 1; height: 8px; background: #e5e7eb; border-radius: 4px; overflow: hidden; }
        .progress-fill { height: 100%; background: var(--success); border-radius: 4px; }
        .progress-value { font-size: 14px; font-weight: 600; font-family: monospace; color: var(--success); min-width: 48px; text-align: right; }
        .flow { background: var(--bg-primary); border: 1px solid var(--border-light); border-radius: 12px; margin-bottom: 12px; overflow: hidden; }
        .flow-header { display: flex; justify-content: space-between; align-items: center; padding: 14px 20px; cursor: pointer; user-select: none; transition: background 0.1s; }
        .flow-header:hover { background: var(--bg-secondary); }
        .flow-header-left { display: flex; align-items: center; gap: 12px; }
        .flow-toggle { width: 20px; color: var(--text-muted); transition: transform 0.2s; }
        .flow.collapsed .flow-toggle { transform: rotate(-90deg); }
        .flow-status { font-size: 16px; }
        .flow.passed .flow-status { color: var(--success); }
        .flow.failed .flow-status { color: var(--failure); }
        .flow-name { font-weight: 600; font-size: 14px; }
        .flow-duration { font-size: 13px; color: var(--text-secondary); font-family: monospace; }
        .steps { border-top: 1px solid var(--border-light); }
        .flow.collapsed .steps { display: none; }
        .step { display: flex; align-items: flex-start; padding: 8px 20px; border-bottom: 1px solid var(--border-light); font-family: monospace; font-size: 13px; }
        .step:last-child { border-bottom: none; }
        .step:hover { background: var(--bg-secondary); }
        .step-status { width: 20px; margin-right: 8px; font-weight: 600; }
        .step.passed .step-status { color: var(--success); }
        .step.failed .step-status { color: var(--failure); }
        .step-index { color: var(--text-muted); margin-right: 8px; min-width: 32px; }
        .step-content { flex: 1; display: flex; justify-content: space-between; gap: 12px; }
        .step-name { word-break: break-word; }
        .step-name .cmd-type { color: #0891b2; font-weight: 500; }
        .step-name .cmd-target { color: var(--text-secondary); }
        .step-meta { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
        .step-duration { color: var(--text-muted); white-space: nowrap; }
        .step-error { margin: 4px 20px 8px 60px; padding: 8px 12px; background: #fee2e2; border-left: 3px solid var(--failure); border-radius: 0 6px 6px 0; color: #b91c1c; font-size: 12px; font-family: monospace; }
        .step-screenshot { height: 28px; border: 2px solid var(--failure); border-radius: 4px; cursor: pointer; opacity: 0.9; transition: opacity 0.15s, transform 0.15s; }
        .step-screenshot:hover { opacity: 1; transform: scale(1.1); }
        .subflow-header { background: var(--bg-secondary); cursor: pointer; }
        .subflow-children { padding-left: 24px; }
        .subflow.collapsed .subflow-children { display: none; }
        .footer { text-align: center; padding: 24px; margin-top: 24px; color: var(--text-muted); font-size: 13px; }
        /* Suite/Test styles */
        .suite { background: var(--bg-primary); border: 1px solid var(--border-light); border-radius: 12px; margin-bottom: 12px; overflow: hidden; }
        .suite-header { display: flex; justify-content: space-between; align-items: center; padding: 14px 20px; cursor: pointer; user-select: none; background: var(--bg-secondary); border-bottom: 1px solid var(--border-light); }
        .suite-header:hover { background: #eef2f7; }
        .suite-header-left { display: flex; align-items: center; gap: 12px; }
        .suite-toggle { width: 20px; color: var(--text-muted); transition: transform 0.2s; }
        .suite.collapsed .suite-toggle { transform: rotate(-90deg); }
        .suite-status { font-size: 16px; }
        .suite.passed .suite-status { color: var(--success); }
        .suite.failed .suite-status { color: var(--failure); }
        .suite-name { font-weight: 600; font-size: 15px; }
        .suite-stats { font-size: 13px; color: var(--text-secondary); font-family: monospace; }
        .suite-tests { border-top: 1px solid var(--border-light); }
        .suite.collapsed .suite-tests { display: none; }
        .test { border-bottom: 1px solid var(--border-light); }
        .test:last-child { border-bottom: none; }
        .test-header { display: flex; justify-content: space-between; align-items: center; padding: 10px 20px; cursor: pointer; }
        .test-header:hover { background: var(--bg-secondary); }
        .test-header-left { display: flex; align-items: center; gap: 10px; }
        .test-status { font-size: 14px; }
        .test.passed .test-status { color: var(--success); }
        .test.failed .test-status { color: var(--failure); }
        .test-name { font-size: 14px; }
        .test-file { font-size: 12px; color: var(--text-muted); margin-left: 8px; }
        .test-duration { font-size: 13px; color: var(--text-secondary); font-family: monospace; }
        .test-steps { padding-left: 20px; background: var(--bg-secondary); }
        .test.collapsed .test-steps { display: none; }
        .tabs { display: flex; gap: 8px; margin-bottom: 16px; }
        .tab { padding: 8px 16px; border: 1px solid var(--border-light); border-radius: 8px; cursor: pointer; font-size: 14px; background: var(--bg-primary); }
        .tab.active { background: var(--accent); color: white; border-color: var(--accent); }
        .tab-content { display: none; }
        .tab-content.active { display: block; }
        .modal { display: none; position: fixed; inset: 0; background: rgba(0,0,0,0.9); align-items: center; justify-content: center; z-index: 1000; padding: 32px; }
        .modal.active { display: flex; }
        .modal img { max-width: 100%; max-height: 100%; border-radius: 8px; }
        .modal-close { position: absolute; top: 20px; right: 20px; width: 44px; height: 44px; display: flex; align-items: center; justify-content: center; color: white; background: rgba(255,255,255,0.1); border: none; border-radius: 50%; cursor: pointer; font-size: 28px; }
        .modal-close:hover { background: rgba(255,255,255,0.2); }
        /* Step Details Panel */
        .step-details-panel { display: none; position: fixed; top: 0; right: 0; width: 450px; height: 100%; background: var(--bg-primary); box-shadow: -4px 0 20px rgba(0,0,0,0.15); z-index: 1000; overflow-y: auto; }
        .step-details-panel.active { display: block; }
        .step-details-header { display: flex; justify-content: space-between; align-items: center; padding: 16px 20px; background: var(--bg-secondary); border-bottom: 1px solid var(--border-light); position: sticky; top: 0; }
        .step-details-header h3 { margin: 0; font-size: 16px; font-weight: 600; }
        .step-details-close { width: 32px; height: 32px; display: flex; align-items: center; justify-content: center; background: none; border: 1px solid var(--border-light); border-radius: 6px; cursor: pointer; font-size: 18px; color: var(--text-secondary); }
        .step-details-close:hover { background: var(--bg-secondary); color: var(--text-primary); }
        .step-details-body { padding: 20px; }
        .step-details-status { display: inline-flex; align-items: center; gap: 6px; padding: 6px 12px; border-radius: 6px; font-size: 13px; font-weight: 600; margin-bottom: 16px; }
        .step-details-status.passed { background: #dcfce7; color: #166534; }
        .step-details-status.failed { background: #fee2e2; color: #b91c1c; }
        .step-details-section { margin-bottom: 20px; }
        .step-details-section-title { font-size: 11px; font-weight: 600; text-transform: uppercase; color: var(--text-muted); margin-bottom: 8px; letter-spacing: 0.5px; }
        .step-details-row { display: flex; padding: 8px 0; border-bottom: 1px solid var(--border-light); }
        .step-details-row:last-child { border-bottom: none; }
        .step-details-label { width: 100px; flex-shrink: 0; font-size: 13px; color: var(--text-muted); }
        .step-details-value { flex: 1; font-size: 13px; word-break: break-word; }
        .step-details-value.mono { font-family: monospace; }
        .step-details-error { padding: 12px; background: #fee2e2; border-left: 3px solid var(--failure); border-radius: 0 6px 6px 0; color: #b91c1c; font-size: 12px; font-family: monospace; margin-top: 8px; }
        .step-details-screenshot { margin-top: 12px; }
        .step-details-screenshot img { max-width: 100%; border: 1px solid var(--border-light); border-radius: 8px; cursor: pointer; }
        .step-details-screenshot img:hover { opacity: 0.9; }
        .step-details-overlay { display: none; position: fixed; inset: 0; background: rgba(0,0,0,0.3); z-index: 999; }
        .step-details-overlay.active { display: block; }
        .step.clickable { cursor: pointer; }
        .step.clickable:hover { background: #e0f2fe; }
        @media (max-width: 768px) {
            .container { padding: 12px; }
            .header { padding: 16px; }
            .header-top { flex-direction: column; }
            .stats { gap: 8px; }
            .stat { padding: 10px 12px; min-width: 80px; }
            .stat-value { font-size: 22px; }
            .step { padding: 6px 12px; font-size: 12px; }
        }
    </style>
</head>
<body>
    <div class="container" id="content">Loading...</div>
    <div class="modal" id="modal" onclick="closeModal()">
        <button class="modal-close" onclick="closeModal()">&times;</button>
        <img id="modal-img" src="" alt="Screenshot">
    </div>
    <div class="step-details-overlay" id="step-details-overlay" onclick="closeStepDetails()"></div>
    <div class="step-details-panel" id="step-details-panel">
        <div class="step-details-header">
            <h3>Step Details</h3>
            <button class="step-details-close" onclick="closeStepDetails()">&times;</button>
        </div>
        <div class="step-details-body" id="step-details-body"></div>
    </div>
    <script>
        const REPORT_DATA = {};

        function formatDuration(ms) {
            if (ms < 1000) return ms + 'ms';
            const secs = ms / 1000;
            if (secs < 60) return secs.toFixed(2) + 's';
            const mins = Math.floor(secs / 60);
            const remainingSecs = (secs % 60).toFixed(0);
            return mins + 'm ' + remainingSecs + 's';
        }

        function formatDate(dateStr) {
            const d = new Date(dateStr);
            return d.toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric', year: 'numeric' });
        }

        function formatTime(dateStr) {
            const d = new Date(dateStr);
            return d.toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit' });
        }

        function escapeHtml(str) {
            if (!str) return '';
            return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
        }

        function renderCommands(commands, depth = 0) {
            if (!commands || commands.length === 0) return '';
            let html = '';
            commands.forEach((cmd, i) => {
                const hasChildren = cmd.commands && cmd.commands.length > 0;
                const statusIcon = cmd.status === 'passed' ? '✓' : '✗';
                const statusClass = cmd.status;
                const target = cmd.subFlow ? cmd.subFlow.name : (cmd.description || '');
                const indent = '│'.repeat(depth);

                if (hasChildren) {
                    const subflowId = 'subflow-' + Math.random().toString(36).substr(2, 9);
                    html += '<div class="subflow" id="' + subflowId + '">';
                    html += '<div class="step subflow-header ' + statusClass + '" onclick="toggleSubflow(\'' + subflowId + '\')">';
                    html += '<span class="step-status">' + statusIcon + '</span>';
                    html += '<span class="step-index">[' + (cmd.index + 1) + ']</span>';
                    html += '<div class="step-content"><span class="step-name"><span class="cmd-type">' + escapeHtml(cmd.type) + ':</span> <span class="cmd-target">' + escapeHtml(target) + '</span></span>';
                    html += '<span class="step-duration">(' + formatDuration(cmd.durationMs) + ')</span></div></div>';
                    html += '<div class="subflow-children">' + renderCommands(cmd.commands, depth + 1) + '</div></div>';
                } else {
                    const stepData = encodeURIComponent(JSON.stringify(cmd));
                    html += '<div class="step clickable ' + statusClass + '" onclick="showStepDetails(\'' + stepData + '\')">';
                    html += '<span class="step-status">' + statusIcon + '</span>';
                    html += '<span class="step-index">[' + (cmd.index + 1) + ']</span>';
                    html += '<div class="step-content"><span class="step-name"><span class="cmd-type">' + escapeHtml(cmd.type) + ':</span> <span class="cmd-target">' + escapeHtml(target) + '</span></span>';
                    html += '<div class="step-meta"><span class="step-duration">(' + formatDuration(cmd.durationMs) + ')</span>';
                    if (cmd.screenshot) {
                        html += '<img class="step-screenshot" src="' + escapeHtml(cmd.screenshot) + '" onclick="openModal(\'' + escapeHtml(cmd.screenshot) + '\'); event.stopPropagation();" title="Click to enlarge">';
                    }
                    html += '</div></div></div>';
                    if (cmd.error && cmd.status === 'failed') {
                        html += '<div class="step-error">Error: ' + escapeHtml(cmd.error) + '</div>';
                    }
                }
            });
            return html;
        }

        function renderSuites(suites) {
            let html = '';
            suites.forEach((suite, i) => {
                const suiteId = 'suite-' + i;
                const passedTests = suite.tests.filter(t => t.status === 'passed').length;
                const failedTests = suite.tests.filter(t => t.status === 'failed').length;
                const suiteStatus = failedTests > 0 ? 'failed' : 'passed';
                const statusIcon = suiteStatus === 'passed' ? '✓' : '✗';

                html += '<div class="suite ' + suiteStatus + '" id="' + suiteId + '">';
                html += '<div class="suite-header" onclick="toggleSuite(\'' + suiteId + '\')">';
                html += '<div class="suite-header-left"><span class="suite-toggle">▼</span><span class="suite-status">' + statusIcon + '</span>';
                html += '<span class="suite-name">' + escapeHtml(suite.suite) + '</span></div>';
                html += '<span class="suite-stats">' + passedTests + ' passed, ' + failedTests + ' failed</span></div>';
                html += '<div class="suite-tests">';

                suite.tests.forEach((test, j) => {
                    const testId = suiteId + '-test-' + j;
                    const testStatusIcon = test.status === 'passed' ? '✓' : '✗';
                    html += '<div class="test ' + test.status + ' collapsed" id="' + testId + '">';
                    html += '<div class="test-header" onclick="toggleTest(\'' + testId + '\')">';
                    html += '<div class="test-header-left"><span class="test-status">' + testStatusIcon + '</span>';
                    html += '<span class="test-name">' + escapeHtml(test.test) + '</span>';
                    if (test.file) {
                        html += '<span class="test-file">(' + escapeHtml(test.file) + ')</span>';
                    }
                    html += '</div>';
                    html += '<span class="test-duration">' + formatDuration(test.durationMs) + '</span></div>';
                    if (test.steps && test.steps.length > 0) {
                        html += '<div class="test-steps">' + renderCommands(test.steps) + '</div>';
                    }
                    if (test.error) {
                        html += '<div class="step-error">Error: ' + escapeHtml(test.error) + '</div>';
                    }
                    html += '</div>';
                });

                html += '</div></div>';
            });
            return html;
        }

        function renderReport(data) {
            const s = data.summary;
            const d = data.device;
            const hasSuites = data.suites && data.suites.length > 0;

            // Calculate stats based on suites if available
            let totalTests = 0, passedTests = 0, failedTests = 0;
            if (hasSuites) {
                data.suites.forEach(suite => {
                    totalTests += suite.tests.length;
                    passedTests += suite.tests.filter(t => t.status === 'passed').length;
                    failedTests += suite.tests.filter(t => t.status === 'failed').length;
                });
            }
            const passRate = hasSuites
                ? (totalTests > 0 ? (passedTests / totalTests * 100).toFixed(0) : 0)
                : (s.totalFlows > 0 ? (s.passedFlows / s.totalFlows * 100).toFixed(0) : 0);

            let html = '<div class="header"><div class="header-top"><div><h1>Maestro Test Results</h1>';
            html += '<div class="header-meta">' + formatDate(s.startTime) + ' at ' + formatTime(s.startTime) + '</div></div>';
            if (d) {
                html += '<div class="device-badge"><div><strong>' + escapeHtml(d.name) + '</strong><span>' + escapeHtml(d.platform) + ' ' + escapeHtml(d.osVersion) + '</span></div></div>';
            }
            html += '</div><div class="stats">';

            if (hasSuites) {
                html += '<div class="stat"><span class="stat-value">' + data.suites.length + '</span><span class="stat-label">Suites</span></div>';
                html += '<div class="stat"><span class="stat-value">' + totalTests + '</span><span class="stat-label">Tests</span></div>';
                html += '<div class="stat passed"><span class="stat-value">' + passedTests + '</span><span class="stat-label">Passed</span></div>';
                html += '<div class="stat failed"><span class="stat-value">' + failedTests + '</span><span class="stat-label">Failed</span></div>';
            } else {
                html += '<div class="stat"><span class="stat-value">' + s.totalFlows + '</span><span class="stat-label">Total</span></div>';
                html += '<div class="stat passed"><span class="stat-value">' + s.passedFlows + '</span><span class="stat-label">Passed</span></div>';
                html += '<div class="stat failed"><span class="stat-value">' + s.failedFlows + '</span><span class="stat-label">Failed</span></div>';
            }
            html += '<div class="stat duration"><span class="stat-value">' + formatDuration(s.totalDurationMs) + '</span><span class="stat-label">Duration</span></div>';
            html += '</div><div class="progress-row"><span class="progress-label">Pass Rate</span>';
            html += '<div class="progress-bar"><div class="progress-fill" style="width: ' + passRate + '%"></div></div>';
            html += '<span class="progress-value">' + passRate + '%</span></div></div>';

            // Render tabs if we have both suites and flows
            if (hasSuites) {
                html += '<div class="tabs">';
                html += '<div class="tab active" onclick="switchTab(\'suites\')">Suites</div>';
                html += '<div class="tab" onclick="switchTab(\'flows\')">Flows</div>';
                html += '</div>';
                html += '<div id="tab-suites" class="tab-content active">' + renderSuites(data.suites) + '</div>';
                html += '<div id="tab-flows" class="tab-content">';
            }

            data.flows.forEach((flow, i) => {
                const flowId = 'flow-' + i;
                const statusIcon = flow.status === 'passed' ? '✓' : '✗';
                html += '<div class="flow ' + flow.status + '" id="' + flowId + '">';
                html += '<div class="flow-header" onclick="toggleFlow(\'' + flowId + '\')">';
                html += '<div class="flow-header-left"><span class="flow-toggle">▼</span><span class="flow-status">' + statusIcon + '</span>';
                html += '<span class="flow-name">' + escapeHtml(flow.name) + '</span></div>';
                html += '<span class="flow-duration">' + formatDuration(flow.durationMs) + '</span></div>';
                html += '<div class="steps">' + renderCommands(flow.commands) + '</div></div>';
            });

            if (hasSuites) {
                html += '</div>'; // Close tab-flows
            }

            html += '<div class="footer">Report built by <a href="https://devicelab.dev" target="_blank" style="color: var(--accent); text-decoration: none;">DeviceLab.dev</a><br>Made with ❤️ by engineers who believe quality mobile testing should not require enterprise budgets.</div>';
            document.getElementById('content').innerHTML = html;
        }

        function toggleFlow(id) { document.getElementById(id).classList.toggle('collapsed'); }
        function toggleSubflow(id) { document.getElementById(id).classList.toggle('collapsed'); }
        function toggleSuite(id) { document.getElementById(id).classList.toggle('collapsed'); }
        function toggleTest(id) { document.getElementById(id).classList.toggle('collapsed'); }
        function switchTab(tab) {
            document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
            document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
            document.querySelector('.tab[onclick*="' + tab + '"]').classList.add('active');
            document.getElementById('tab-' + tab).classList.add('active');
        }
        function openModal(src) { document.getElementById('modal-img').src = src; document.getElementById('modal').classList.add('active'); }
        function closeModal() { document.getElementById('modal').classList.remove('active'); }

        function showStepDetails(stepJson) {
            const step = JSON.parse(decodeURIComponent(stepJson));
            const statusIcon = step.status === 'passed' ? '✓' : '✗';

            let html = '<div class="step-details-status ' + step.status + '">' + statusIcon + ' ' + (step.status === 'passed' ? 'Passed' : 'Failed') + '</div>';

            // Command Info Section
            html += '<div class="step-details-section">';
            html += '<div class="step-details-section-title">Command</div>';
            html += '<div class="step-details-row"><span class="step-details-label">Type</span><span class="step-details-value mono">' + escapeHtml(step.type || 'unknown') + '</span></div>';
            html += '<div class="step-details-row"><span class="step-details-label">Description</span><span class="step-details-value">' + escapeHtml(step.description || '') + '</span></div>';
            html += '</div>';

            // Timing Section
            html += '<div class="step-details-section">';
            html += '<div class="step-details-section-title">Timing</div>';
            html += '<div class="step-details-row"><span class="step-details-label">Duration</span><span class="step-details-value mono">' + formatDuration(step.durationMs || 0) + '</span></div>';
            if (step.startTime) {
                html += '<div class="step-details-row"><span class="step-details-label">Start</span><span class="step-details-value mono">' + formatTime(step.startTime) + '</span></div>';
            }
            if (step.endTime) {
                html += '<div class="step-details-row"><span class="step-details-label">End</span><span class="step-details-value mono">' + formatTime(step.endTime) + '</span></div>';
            }
            html += '</div>';

            // Execution Section
            if (step.attempts || step.id) {
                html += '<div class="step-details-section">';
                html += '<div class="step-details-section-title">Execution</div>';
                if (step.id) {
                    html += '<div class="step-details-row"><span class="step-details-label">ID</span><span class="step-details-value mono">' + escapeHtml(step.id) + '</span></div>';
                }
                if (step.attempts) {
                    html += '<div class="step-details-row"><span class="step-details-label">Attempts</span><span class="step-details-value">' + step.attempts + '</span></div>';
                }
                html += '</div>';
            }

            // Error Section
            if (step.error) {
                html += '<div class="step-details-section">';
                html += '<div class="step-details-section-title">Error</div>';
                html += '<div class="step-details-error">' + escapeHtml(step.error) + '</div>';
                html += '</div>';
            }

            // Screenshot Section
            if (step.screenshot) {
                html += '<div class="step-details-section">';
                html += '<div class="step-details-section-title">Screenshot</div>';
                html += '<div class="step-details-screenshot"><img src="' + escapeHtml(step.screenshot) + '" onclick="openModal(\'' + escapeHtml(step.screenshot) + '\')" title="Click to enlarge"></div>';
                html += '</div>';
            }

            document.getElementById('step-details-body').innerHTML = html;
            document.getElementById('step-details-panel').classList.add('active');
            document.getElementById('step-details-overlay').classList.add('active');
        }

        function closeStepDetails() {
            document.getElementById('step-details-panel').classList.remove('active');
            document.getElementById('step-details-overlay').classList.remove('active');
        }

        document.addEventListener('keydown', function(e) { if (e.key === 'Escape') { closeModal(); closeStepDetails(); } });

        if (REPORT_DATA && REPORT_DATA.summary) renderReport(REPORT_DATA);
    </script>
</body>
</html>"""
    }

    private fun generateAllureResults(report: ReportData, dir: Path) {
        val allureDir = dir.resolve("allure-results")
        Files.createDirectories(allureDir)

        // Copy screenshots directly to allure-results (not in subfolder)
        val srcScreenshots = dir.resolve("screenshots")
        if (Files.exists(srcScreenshots)) {
            Files.list(srcScreenshots).forEach { file ->
                Files.copy(file, allureDir.resolve(file.fileName), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }

        // Generate categories.json
        val categories = """[
  { "name": "Element Not Found", "matchedStatuses": ["failed"], "messageRegex": "(?i).*element not found.*" },
  { "name": "Element Not Visible", "matchedStatuses": ["failed"], "messageRegex": "(?i).*not visible.*|.*not displayed.*" },
  { "name": "Timeout", "matchedStatuses": ["failed"], "messageRegex": "(?i).*timeout.*|.*timed out.*" },
  { "name": "Assertion Failed", "matchedStatuses": ["failed"], "messageRegex": "(?i).*assert.*" },
  { "name": "App Launch Failed", "matchedStatuses": ["failed"], "messageRegex": "(?i).*launch.*failed.*|.*clearState.*|.*clear app.*|.*app.*crash.*" },
  { "name": "Connection Error", "matchedStatuses": ["failed"], "messageRegex": "(?i).*connection.*|.*socket.*|.*network.*|.*ECONNREFUSED.*" },
  { "name": "Script Error", "matchedStatuses": ["failed"], "messageRegex": "(?i).*script.*error.*|.*runScript.*|.*javascript.*" },
  { "name": "Input Error", "matchedStatuses": ["failed"], "messageRegex": "(?i).*input.*|.*keyboard.*|.*sendKeys.*" }
]"""
        allureDir.resolve("categories.json").toFile().writeText(categories)

        // Generate environment.properties
        val envProps = buildString {
            appendLine("framework=maestro")
            report.device?.let {
                appendLine("device.name=${it.name}")
                appendLine("device.platform=${it.platform}")
                appendLine("device.osVersion=${it.osVersion}")
            }
        }
        allureDir.resolve("environment.properties").toFile().writeText(envProps)

        // Generate executor.json
        val executor = """{
  "name": "DeviceLab",
  "type": "devicelab",
  "reportUrl": "https://devicelab.dev",
  "reportName": "Powered by DeviceLab"
}"""
        allureDir.resolve("executor.json").toFile().writeText(executor)

        // Check if we have global suites (suite/test structure)
        val globalSuites = report.suites
        if (!globalSuites.isNullOrEmpty()) {
            // Generate separate result file per test with proper suite labels
            generateAllureTestResults(globalSuites, allureDir)
        } else {
            // Fallback: Generate flow results with nested steps (original behavior)
            for (flow in report.flows) {
                generateAllureFlowResult(flow, allureDir)
            }
        }
    }

    /**
     * Generates Allure result files for suite/test structure.
     * Each test becomes a separate result file with suite labels.
     */
    private fun generateAllureTestResults(suites: List<SuiteData>, allureDir: Path) {
        var testCounter = 0
        for (suite in suites) {
            for (test in suite.tests) {
                testCounter++
                val testId = "test_%03d".format(testCounter)
                val stepsJson = buildAllureSteps(test.steps)

                val labelsJson = buildString {
                    append("""{ "name": "suite", "value": "${suite.suite.escapeJson()}" }""")
                    test.file?.let { file ->
                        append(""",
    { "name": "parentSuite", "value": "${file.escapeJson()}" }""")
                    }
                    append(""",
    { "name": "framework", "value": "maestro" },
    { "name": "severity", "value": "normal" }""")
                }

                val result = buildString {
                    appendLine("""{""")
                    appendLine("""  "uuid": "$testId",""")
                    appendLine("""  "historyId": "${testId.hashCode().toString(16)}",""")
                    appendLine("""  "fullName": "${suite.suite.escapeJson()} > ${test.test.escapeJson()}",""")
                    appendLine("""  "name": "${test.test.escapeJson()}",""")
                    appendLine("""  "labels": [""")
                    appendLine("    $labelsJson")
                    appendLine("""  ],""")
                    appendLine("""  "status": "${test.status}",""")
                    appendLine("""  "stage": "finished",""")
                    // Use current time for start/stop since we don't track per-test timestamps
                    val now = System.currentTimeMillis()
                    appendLine("""  "start": ${now - test.durationMs},""")
                    appendLine("""  "stop": $now,""")
                    if (stepsJson.isNotEmpty()) {
                        appendLine("""  "steps": [$stepsJson""")
                        append("""  ]""")
                    } else {
                        append("""  "steps": []""")
                    }
                    test.error?.let { err ->
                        appendLine(",")
                        append("""  "statusDetails": { "message": "${err.escapeJson()}" }""")
                    }
                    test.screenshot?.let { screenshot ->
                        val screenshotFile = screenshot.removePrefix("screenshots/")
                        appendLine(",")
                        append("""  "attachments": [{ "name": "Screenshot", "source": "$screenshotFile", "type": "image/png" }]""")
                    }
                    appendLine()
                    appendLine("""}""")
                }
                allureDir.resolve("$testId-result.json").toFile().writeText(result)
            }
        }
    }

    /**
     * Generates Allure result file for a flow (original behavior without suite/test).
     */
    private fun generateAllureFlowResult(flow: FlowData, allureDir: Path) {
        val stepsJson = buildAllureSteps(flow.commands)
        val tagsJson = flow.tags?.joinToString(",\n    ") { """{ "name": "tag", "value": "${it.escapeJson()}" }""" } ?: ""
        val labelsJson = buildString {
            if (tagsJson.isNotEmpty()) {
                append(tagsJson)
                append(",\n    ")
            }
            append("""{ "name": "framework", "value": "maestro" },
    { "name": "severity", "value": "normal" }""")
        }

        val result = buildString {
            appendLine("""{""")
            appendLine("""  "uuid": "${flow.id}",""")
            appendLine("""  "historyId": "${flow.id.hashCode().toString(16)}",""")
            appendLine("""  "fullName": "${flow.name.escapeJson()}",""")
            appendLine("""  "name": "${flow.name.escapeJson()}",""")
            appendLine("""  "labels": [""")
            appendLine("    $labelsJson")
            appendLine("""  ],""")
            appendLine("""  "status": "${flow.status}",""")
            appendLine("""  "stage": "finished",""")
            appendLine("""  "start": ${parseTimestamp(flow.startTime)},""")
            appendLine("""  "stop": ${parseTimestamp(flow.endTime)},""")
            if (stepsJson.isNotEmpty()) {
                appendLine("""  "steps": [$stepsJson""")
                appendLine("""  ]""")
            } else {
                appendLine("""  "steps": []""")
            }
            flow.error?.let { err ->
                append(""",
  "statusDetails": { "message": "${err.escapeJson()}" }""")
            }
            appendLine()
            appendLine("""}""")
        }
        allureDir.resolve("${flow.id}-result.json").toFile().writeText(result)
    }

    private fun buildAllureSteps(commands: List<CommandData>?, indent: String = "    "): String {
        if (commands.isNullOrEmpty()) return ""
        return commands.mapIndexed { idx, cmd ->
            val nestedSteps = buildAllureSteps(cmd.commands, "$indent  ")
            val hasNested = nestedSteps.isNotEmpty()
            val hasScreenshot = cmd.screenshot != null
            buildString {
                appendLine()
                append("$indent{")
                appendLine()
                appendLine("""$indent  "name": "${cmd.type}: ${cmd.description.escapeJson()}",""")
                appendLine("""$indent  "status": "${cmd.status}",""")
                appendLine("""$indent  "stage": "finished",""")
                appendLine("""$indent  "start": ${parseTimestamp(cmd.startTime)},""")
                append("""$indent  "stop": ${parseTimestamp(cmd.endTime)}""")
                if (hasScreenshot) {
                    // Remove "screenshots/" prefix for allure - files are copied directly to allure-results
                    val screenshotFile = cmd.screenshot!!.removePrefix("screenshots/")
                    appendLine(",")
                    append("""$indent  "attachments": [{ "name": "Screenshot", "source": "$screenshotFile", "type": "image/png" }]""")
                }
                if (hasNested) {
                    appendLine(",")
                    appendLine("""$indent  "steps": [$nestedSteps""")
                    append("""$indent  ]""")
                }
                appendLine()
                append("$indent}")
            }
        }.joinToString(",")
    }

    private fun String.escapeJson() = this
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    private fun parseTimestamp(timestamp: String?): Long {
        if (timestamp == null) return System.currentTimeMillis()
        return try {
            ZonedDateTime.parse(timestamp, isoFormatter).toInstant().toEpochMilli()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
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
    val flows: MutableList<FlowData>,
    var suites: MutableList<SuiteData>? = null  // Global suite aggregation across all flows
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
    val commands: MutableList<CommandData>,
    var suites: MutableList<SuiteData>? = null
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

data class SuiteData(
    val suite: String,
    var status: String,
    var durationMs: Long = 0,
    val tests: MutableList<TestData> = mutableListOf()
)

data class TestData(
    val test: String,
    val file: String? = null,
    var status: String,
    var durationMs: Long = 0,
    var error: String? = null,
    var screenshot: String? = null,
    val steps: MutableList<CommandData> = mutableListOf()
)
