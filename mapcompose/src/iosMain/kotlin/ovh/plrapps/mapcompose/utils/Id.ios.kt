package ovh.plrapps.mapcompose.utils

import platform.Foundation.NSUUID

actual fun generateId(): String = NSUUID().UUIDString()