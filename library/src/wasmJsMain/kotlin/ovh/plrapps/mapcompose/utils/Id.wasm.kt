package ovh.plrapps.mapcompose.utils

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
actual fun generateId(): String = Uuid.random().toString()
