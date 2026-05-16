package nz.kaimahi.agent

internal object NativeAgent {
    private val loaded: Boolean = runCatching { System.loadLibrary("kaimahi_native") }.isSuccess

    @JvmStatic external fun nativeMaxIterations(): Int

    fun maxIterations(): Int =
        if (loaded) runCatching { nativeMaxIterations() }.getOrElse { 50 } else 50
}
