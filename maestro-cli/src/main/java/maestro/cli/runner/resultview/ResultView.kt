package maestro.cli.runner.resultview

interface ResultView {
    fun setState(state: UiState)
    fun close(passed: Boolean, duration: Long? = null, errorMessage: String? = null) {}
}
