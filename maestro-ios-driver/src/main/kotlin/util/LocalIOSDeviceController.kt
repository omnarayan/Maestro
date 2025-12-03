package util

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import util.CommandLineUtils.runCommand
import java.io.File
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

object LocalIOSDeviceController {

    private val logger = LoggerFactory.getLogger(LocalIOSDeviceController::class.java)
    private const val LOG_DIR_DATE_FORMAT = "yyyy-MM-dd_HHmmss"
    private val dateFormatter by lazy { DateTimeFormatter.ofPattern(LOG_DIR_DATE_FORMAT) }
    private val date = dateFormatter.format(LocalDateTime.now())

    fun install(deviceId: String, path: Path) {
        runCommand(
            listOf(
                "xcrun",
                "devicectl",
                "device",
                "install",
                "app",
                "--device",
                deviceId,
                path.toAbsolutePath().toString(),
            )
        )
    }

    fun launchRunner(deviceId: String, port: Int, snapshotKeyHonorModalViews: Boolean?) {
        val outputFile = File(XCRunnerCLIUtils.logDirectory, "xctest_runner_$date.log")
        val params = mutableMapOf("SIMCTL_CHILD_PORT" to port.toString())
        if (snapshotKeyHonorModalViews != null) {
            params["SIMCTL_CHILD_snapshotKeyHonorModalViews"] = snapshotKeyHonorModalViews.toString()
        }
        runCommand(
            listOf(
                "xcrun",
                "devicectl",
                "device",
                "process",
                "launch",
                "--terminate-existing",
                "--device",
                deviceId,
                "dev.mobile.maestro-driver-iosUITests.xctrunner"
            ),
            params = params,
            waitForCompletion = false,
            outputFile = outputFile
        )
    }

    fun terminate(deviceId: String, bundleId: String) {
        logger.info("[Start] Terminating app $bundleId on device $deviceId")
        val pid = getProcessId(deviceId, bundleId)
        if (pid != null) {
            runCatching {
                val process = ProcessBuilder(
                    listOf(
                        "xcrun",
                        "devicectl",
                        "device",
                        "process",
                        "terminate",
                        "--device",
                        deviceId,
                        "--pid",
                        pid.toString()
                    )
                ).redirectError(ProcessBuilder.Redirect.PIPE).start()

                val completed = process.waitFor(10, TimeUnit.SECONDS)
                if (!completed) {
                    logger.warn("Timeout waiting for terminate command")
                    process.destroyForcibly()
                }
            }.onFailure {
                logger.warn("Failed to terminate app $bundleId: ${it.message}")
            }
            logger.info("[Done] Terminating app $bundleId")
        } else {
            logger.info("App $bundleId is not running, nothing to terminate")
        }
    }

    private fun getProcessId(deviceId: String, bundleId: String): Int? {
        val tempOutput = File.createTempFile("devicectl_processes", ".json")
        try {
            val process = ProcessBuilder(
                listOf(
                    "xcrun",
                    "devicectl",
                    "device",
                    "info",
                    "processes",
                    "--device",
                    deviceId,
                    "--json-output",
                    tempOutput.path
                )
            ).redirectError(ProcessBuilder.Redirect.PIPE).start()

            val completed = process.waitFor(10, TimeUnit.SECONDS)
            if (!completed) {
                logger.warn("Timeout waiting for process list from device $deviceId")
                process.destroyForcibly()
                return null
            }

            val response = tempOutput.readText()
            val processResponse = jacksonObjectMapper().readValue<ProcessListResponse>(response)

            return processResponse.result?.runningProcesses?.find {
                it.bundleIdentifier == bundleId
            }?.processIdentifier
        } catch (e: Exception) {
            logger.warn("Failed to get process ID for $bundleId: ${e.message}")
            return null
        } finally {
            tempOutput.delete()
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ProcessListResponse(
        val result: ProcessResult?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ProcessResult(
        val runningProcesses: List<ProcessInfo>?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ProcessInfo(
        val processIdentifier: Int?,
        val bundleIdentifier: String?
    )
}