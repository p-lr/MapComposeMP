package ovh.plrapps.mapcompose.utils

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

actual val IODispatcher: CoroutineDispatcher = Dispatchers.IO
