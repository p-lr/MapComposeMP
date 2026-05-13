@file:OptIn(ExperimentalKotlinGradlePluginApi::class, ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatform)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.vanniktechPublish)
}

kotlin {
    targets.configureEach {
        compilations.configureEach {
            compileTaskProvider.get().compilerOptions {
                freeCompilerArgs.addAll(
                    "-Xexpect-actual-classes",
                    "-opt-in=kotlinx.coroutines.FlowPreview",
                    "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
                    "-opt-in=kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi",
                    "-Xannotation-default-target=param-property",
                    "-Xcontext-parameters",
                )
            }
        }
    }

    android {
        namespace = "ovh.plrapps.mapcomposemp"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
            freeCompilerArgs.add("-jvm-default=enable")
        }
        withHostTest {}
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
            freeCompilerArgs.add("-jvm-default=enable")
        }
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "MapComposeMP"
            isStatic = true
        }
    }

    wasmJs {
        browser()
        nodejs()
        d8()
    }

    sourceSets {
        val desktopMain by getting
        val wasmJsMain by getting

        androidMain.dependencies {
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.compose.ui.tooling)
            implementation(libs.androidx.activity.compose)
        }
        @Suppress("DEPRECATION")
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.kotlinx.coroutines)
            api(libs.kotlinx.io.core)
        }
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.skiko)
        }
        iosMain.dependencies {
            implementation(libs.skiko)
        }
        wasmJsMain.dependencies {
            implementation(libs.skiko)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}


tasks.register("testClasses")
tasks.register("testDebugUnitTest") { dependsOn("testAndroid") }

ext["signing.keyId"] = System.getenv("signingKeyId")
ext["signing.password"] = System.getenv("signingPwd")
ext["signing.secretKeyRingFile"] = System.getenv("signingKeyFile")

mavenPublishing {
    publishToMavenCentral(false)
    signAllPublications()
    coordinates("ovh.plrapps", "mapcompose-mp")
    pom {
        name = "MapComposeMP"
        description = "A Compose Multiplatform library to display tiled maps, with support for markers, paths, and rotation"
        url = "https://github.com/p-lr/MapComposeMP"

        licenses {
            license {
                name = "The Apache Software License, Version 2.0"
                url = "http://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "http://www.apache.org/licenses/LICENSE-2.0.txt"
            }
        }

        developers {
            developer {
                id = "p-lr"
                name = "Pierre Laurence"
                url = "https://github.com/p-lr/"
            }
        }
        scm {
            connection = "scm:git@github.com:p-lr/MapComposeMP.git"
            developerConnection = "scm:git@github.com:p-lr/MapComposeMP.git"
            url = "https://github.com/p-lr/MapComposeMP"
        }
    }
}
