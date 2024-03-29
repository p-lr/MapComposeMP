package ovh.plrapps.mapcompose.demo.providers

import kotlinx.io.asSource
import ovh.plrapps.mapcompose.demo.utils.AndroidInjector

actual val assetFileProvider: AssetFileProvider = AssetFileProvider { path ->
    AndroidInjector.application.classLoader.getResourceAsStream(path).asSource()
}
