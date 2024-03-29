package ovh.plrapps.mapcompose.demo.providers

import kotlinx.io.asSource

actual val assetFileProvider: AssetFileProvider = AssetFileProvider { path ->
    any.javaClass.classLoader.getResourceAsStream(path)?.asSource()
}

private val any = object {}