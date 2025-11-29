package ios.devicectl

import com.github.michaelbull.result.Result
import device.IOSDevice
import device.IOSScreenRecording
import hierarchy.ViewHierarchy
import okio.Sink
import org.slf4j.LoggerFactory
import util.LocalIOSDevice
import util.LocalIOSDeviceController
import xcuitest.api.DeviceInfo
import xcuitest.installer.LocalXCTestInstaller
import java.io.InputStream
import java.nio.file.Paths

class DeviceControlIOSDevice(override val deviceId: String) : IOSDevice {

    private val localIOSDevice by lazy { LocalIOSDevice() }

    companion object {
        private val logger = LoggerFactory.getLogger(DeviceControlIOSDevice::class.java)
    }

    override fun open() {
        TODO("Not yet implemented")
    }

    override fun deviceInfo(): DeviceInfo {
        TODO("Not yet implemented")
    }

    override fun viewHierarchy(excludeKeyboardElements: Boolean): ViewHierarchy {
        TODO("Not yet implemented")
    }

    override fun tap(x: Int, y: Int) {
        TODO("Not yet implemented")
    }

    override fun longPress(x: Int, y: Int, durationMs: Long) {
        TODO("Not yet implemented")
    }

    override fun scroll(xStart: Double, yStart: Double, xEnd: Double, yEnd: Double, duration: Double) {
        TODO("Not yet implemented")
    }

    override fun input(text: String) {
        TODO("Not yet implemented")
    }

    override fun install(stream: InputStream) {
        // For real devices, we read app path from system property set by CLI
        val appFilePath = System.getProperty("maestro.appFile")
        if (appFilePath != null) {
            val appPath = Paths.get(appFilePath)
            if (!appPath.toFile().exists()) {
                throw IllegalStateException(
                    "App file not found: $appFilePath\n" +
                    "Please ensure the --app-file path is correct and the file exists."
                )
            }
            logger.info("Installing app from: $appFilePath")
            try {
                LocalIOSDeviceController.install(deviceId, appPath)
            } catch (e: Exception) {
                throw IllegalStateException(
                    "Failed to install app on device $deviceId\n" +
                    "App file: $appFilePath\n" +
                    "Error: ${e.message}\n" +
                    "Possible causes:\n" +
                    "  - App is not signed for this device\n" +
                    "  - Device is not in developer mode\n" +
                    "  - App bundle is corrupted or invalid\n" +
                    "  - Device storage is full",
                    e
                )
            }
        } else {
            throw IllegalStateException(
                "No app file specified for installation.\n" +
                "Use --app-file to provide the app binary (.ipa or .app) for installation.\n" +
                "Example: maestro test --app-file /path/to/app.ipa flow.yaml"
            )
        }
    }

    override fun uninstall(id: String) {
        localIOSDevice.uninstall(deviceId, id)
    }

    override fun clearAppState(id: String) {
        val appFilePath = System.getProperty("maestro.appFile")
        if (appFilePath == null) {
            logger.warn(
                "clearState requires --app-file on real iOS devices. " +
                "App state will NOT be cleared. " +
                "Use: maestro test --app-file /path/to/app.ipa flow.yaml"
            )
            return
        }

        logger.info("Clearing app state for $id by reinstalling")

        // 1. Stop the app
        stop(id)

        // 2. Uninstall the app
        try {
            uninstall(id)
        } catch (e: Exception) {
            logger.warn("Failed to uninstall app $id (may not be installed): ${e.message}")
        }

        // 3. Reinstall the app
        install(ByteArray(0).inputStream()) // stream is ignored, uses System property

        logger.info("App state cleared for $id")
    }

    override fun clearKeychain(): Result<Unit, Throwable> {
        TODO("Not yet implemented")
    }

    override fun launch(id: String, launchArguments: Map<String, Any>) {
        TODO("Not yet implemented")
    }

    override fun stop(id: String) {
        LocalIOSDeviceController.terminate(deviceId, id)
    }

    override fun isKeyboardVisible(): Boolean {
        TODO("Not yet implemented")
    }

    override fun openLink(link: String): Result<Unit, Throwable> {
        TODO("Not yet implemented")
    }

    override fun takeScreenshot(out: Sink, compressed: Boolean) {
        TODO("Not yet implemented")
    }

    override fun startScreenRecording(out: Sink): Result<IOSScreenRecording, Throwable> {
        TODO("Not yet implemented")
    }

    override fun setLocation(latitude: Double, longitude: Double): Result<Unit, Throwable> {
        TODO("Not yet implemented")
    }

    override fun setOrientation(orientation: String) {
        TODO("Not yet implemented")
    }

    override fun isShutdown(): Boolean {
        TODO("Not yet implemented")
    }

    override fun isScreenStatic(): Boolean {
        TODO("Not yet implemented")
    }

    override fun setPermissions(id: String, permissions: Map<String, String>) {
        /* noop */
    }

    override fun pressKey(name: String) {
        TODO("Not yet implemented")
    }

    override fun pressButton(name: String) {
        TODO("Not yet implemented")
    }

    override fun eraseText(charactersToErase: Int) {
        TODO("Not yet implemented")
    }

    override fun addMedia(path: String) {
        TODO("Not yet implemented")
    }

    override fun close() {
        // Skip uninstalling runner app on real devices to allow session reuse
        // logger.info("[Start] Uninstall the runner app")
        // uninstall(id = LocalXCTestInstaller.UI_TEST_RUNNER_APP_BUNDLE_ID)
        // logger.info("[Done] Uninstall the runner app")
    }
}