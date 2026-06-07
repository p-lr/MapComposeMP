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
    applyDefaultHierarchyTemplate {
        common {
            group("skia") {
                withIos()
                withJvm()
                withWasmJs()
            }
        }
    }

    targets.configureEach {
        compilations.configureEach {
            compileTaskProvider.get().compilerOptions {
                freeCompilerArgs.addAll(
                    "-Xexpect-actual-classes",
                    "-opt-in=kotlinx.coroutines.FlowPreview",
                    "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
                )
            }
        }
    }

    android {
        namespace = "ovh.plrapps.mapcomposemp"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.add("-jvm-default=enable")
        }
        withHostTest {}
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
            baseName = "MapComposeMP"
            isStatic = true
        }
    }

    wasmJs {
        browser()
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.compose.ui.tooling)
        }
        commonMain.dependencies {
            implementation(libs.jetbrains.compose.runtime)
            implementation(libs.jetbrains.compose.foundation)
            implementation(libs.jetbrains.compose.ui)
            implementation(libs.kotlinx.coroutines)
            api(libs.kotlinx.io.core)
        }
        named("skiaMain").configure {
            dependencies {
                // Place dependencies here if needed (none for now).
                // This source set makes skiko visible as transitive dependency of compose.ui,
                // because androidMain does not depend on skiaMain.
            }
        }
        getByName("desktopMain").dependencies {
            implementation(compose.desktop.currentOs)
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
