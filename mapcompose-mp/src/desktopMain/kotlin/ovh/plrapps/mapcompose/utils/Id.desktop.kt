package ovh.plrapps.mapcompose.utils

import java.util.UUID

actual fun generateId(): String = UUID.randomUUID().toString()