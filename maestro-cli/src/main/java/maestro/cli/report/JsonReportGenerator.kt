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
        command.screenshot = screenshot
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

        // Generate other report formats
        generateJUnitReport(report, dir)
        generateHtmlReport(report, dir)
        generateAllureResults(report, dir)

        return dir
    }

    fun printReportPaths() {
        val dir = reportDir ?: return
        val absPath = dir.toAbsolutePath()
        println()
        println("Reports:")
        println("  HTML:   $absPath/index.html")
        println("  JSON:   $absPath/report.json")
        println("  JUnit:  $absPath/junit-report.xml")
        println("  Allure: $absPath/allure-results")
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
        .modal { display: none; position: fixed; inset: 0; background: rgba(0,0,0,0.9); align-items: center; justify-content: center; z-index: 1000; padding: 32px; }
        .modal.active { display: flex; }
        .modal img { max-width: 100%; max-height: 100%; border-radius: 8px; }
        .modal-close { position: absolute; top: 20px; right: 20px; width: 44px; height: 44px; display: flex; align-items: center; justify-content: center; color: white; background: rgba(255,255,255,0.1); border: none; border-radius: 50%; cursor: pointer; font-size: 28px; }
        .modal-close:hover { background: rgba(255,255,255,0.2); }
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
                    html += '<div class="step ' + statusClass + '">';
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

        function renderReport(data) {
            const s = data.summary;
            const d = data.device;
            const passRate = s.totalFlows > 0 ? (s.passedFlows / s.totalFlows * 100).toFixed(0) : 0;

            let html = '<div class="header"><div class="header-top"><div><h1>Maestro Test Results</h1>';
            html += '<div class="header-meta">' + formatDate(s.startTime) + ' at ' + formatTime(s.startTime) + '</div></div>';
            if (d) {
                html += '<div class="device-badge"><div><strong>' + escapeHtml(d.name) + '</strong><span>' + escapeHtml(d.platform) + ' ' + escapeHtml(d.osVersion) + '</span></div></div>';
            }
            html += '</div><div class="stats">';
            html += '<div class="stat"><span class="stat-value">' + s.totalFlows + '</span><span class="stat-label">Total</span></div>';
            html += '<div class="stat passed"><span class="stat-value">' + s.passedFlows + '</span><span class="stat-label">Passed</span></div>';
            html += '<div class="stat failed"><span class="stat-value">' + s.failedFlows + '</span><span class="stat-label">Failed</span></div>';
            html += '<div class="stat duration"><span class="stat-value">' + formatDuration(s.totalDurationMs) + '</span><span class="stat-label">Duration</span></div>';
            html += '</div><div class="progress-row"><span class="progress-label">Pass Rate</span>';
            html += '<div class="progress-bar"><div class="progress-fill" style="width: ' + passRate + '%"></div></div>';
            html += '<span class="progress-value">' + passRate + '%</span></div></div>';

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

            html += '<div class="footer">Generated by Maestro</div>';
            document.getElementById('content').innerHTML = html;
        }

        function toggleFlow(id) { document.getElementById(id).classList.toggle('collapsed'); }
        function toggleSubflow(id) { document.getElementById(id).classList.toggle('collapsed'); }
        function openModal(src) { document.getElementById('modal-img').src = src; document.getElementById('modal').classList.add('active'); }
        function closeModal() { document.getElementById('modal').classList.remove('active'); }
        document.addEventListener('keydown', function(e) { if (e.key === 'Escape') closeModal(); });

        if (REPORT_DATA && REPORT_DATA.summary) renderReport(REPORT_DATA);
    </script>
</body>
</html>"""
    }

    private fun generateAllureResults(report: ReportData, dir: Path) {
        val allureDir = dir.resolve("allure-results")
        Files.createDirectories(allureDir)

        for (flow in report.flows) {
            val result = buildString {
                appendLine("""{""")
                appendLine("""  "uuid": "${java.util.UUID.randomUUID()}",""")
                appendLine("""  "historyId": "${flow.id}",""")
                appendLine("""  "name": "${flow.name.replace("\"", "\\\"")}",""")
                appendLine("""  "status": "${flow.status}",""")
                appendLine("""  "stage": "finished",""")
                appendLine("""  "start": ${parseTimestamp(flow.startTime)},""")
                appendLine("""  "stop": ${parseTimestamp(flow.endTime)},""")
                flow.error?.let { err ->
                    appendLine("""  "statusDetails": { "message": "${err.replace("\"", "\\\"")}" },""")
                }
                appendLine("""  "labels": [""")
                appendLine("""    { "name": "suite", "value": "Maestro Tests" },""")
                appendLine("""    { "name": "framework", "value": "maestro" }""")
                appendLine("""  ]""")
                appendLine("""}""")
            }
            allureDir.resolve("${flow.id}-result.json").toFile().writeText(result)
        }
    }

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
