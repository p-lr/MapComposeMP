@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatform)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    targets.configureEach {
        compilations.configureEach {
            compileTaskProvider.get().compilerOptions {
                freeCompilerArgs.addAll(
                    "-Xexpect-actual-classes",
                    "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
                    "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
                    "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
                    "-opt-in=kotlinx.coroutines.FlowPreview",
                    "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
                    "-opt-in=kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi",
                    "-Xannotation-default-target=param-property",
                    "-Xcontext-parameters"
                )
            }
        }
    }

    android {
        namespace = "ovh.plrapps.mapcomposemp.demo"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.add("-jvm-default=enable")
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.add("-jvm-default=enable")
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        val desktopMain by getting

        androidMain.dependencies {
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.ktor.client.android)
        }
        commonMain.dependencies {
            implementation(libs.jetbrains.compose.runtime)
            implementation(libs.jetbrains.compose.foundation)
            implementation(libs.jetbrains.compose.material3)
            implementation(libs.jetbrains.compose.ui)
            implementation(libs.jetbrains.compose.ui.tooling.preview)
            implementation(libs.components.resources)
            implementation(libs.kotlinx.coroutines)
            implementation(libs.ktor.client.core)
            implementation(libs.jetbrains.navigation.compose)
            implementation(libs.jetbrains.lifecycle.viewmodel)
            implementation(libs.jetbrains.lifecycle.viewmodel.compose)
            implementation(libs.kotlinx.serialization.core)
            implementation(project(":library"))
        }
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.ktor.client.android)
            implementation(libs.kotlinx.coroutines.swing)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

compose.desktop {
    application {
        mainClass = "MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "ovh.plrapps.mapcomposemp.demo"
            packageVersion = "1.0.0"
        }
    }
}

compose.resources {
    publicResClass = false
    packageOfResClass = "ovh.plrapps.mapcomposemp.demo"
    generateResClass = auto
}

tasks.register("testClasses")
