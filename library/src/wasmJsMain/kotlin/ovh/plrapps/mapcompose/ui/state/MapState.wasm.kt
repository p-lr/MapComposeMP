package ovh.plrapps.mapcompose.ui.state

/* This function is somewhat misleading in wasmJs - this dictates how many coroutines are used for fetching map tiles,
     however, fetching on the web is done asynchronously already, so this is limited less by thread/processor count and
     more by how many concurrent connections the browser supports, or a map tile platform allows. */
actual fun getProcessorCount(): Int {
    return 8
}
