package ovh.plrapps.mapcompose.ui.state

actual fun getProcessorCount(): Int {
    return Runtime.getRuntime().availableProcessors()
}