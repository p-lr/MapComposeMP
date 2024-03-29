package ovh.plrapps.mapcompose.demo.utils

import android.app.Application

object AndroidInjector {
    private var app: Application? = null
    internal val application
        get() = app ?: throw IllegalStateException("Application not initialized")


    fun init(application: Application) {
        this.app = application
    }
}