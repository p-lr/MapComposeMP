plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.jetbrainsCompose)
    `maven-publish`
    signing
}

kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "11"
            }
        }
        publishLibraryVariants("release")
    }
    
    jvm("desktop")

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
    
    sourceSets {
        val desktopMain by getting
        
        androidMain.dependencies {
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.androidx.activity.compose)
        }
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.kotlinx.coroutines)
            implementation(libs.skia)
            api(libs.kotlinx.io.core)
        }
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
        }
        iosMain.dependencies {

        }
    }
}

android {
    namespace = "ovh.plrapps.mapcomposemp"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    sourceSets["main"].res.srcDirs("src/androidMain/res")
    sourceSets["main"].resources.srcDirs("src/commonMain/resources")

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    dependencies {
        debugImplementation(libs.compose.ui.tooling)
    }
}

task("testClasses")

val GROUP: String by project
val VERSION_NAME: String by project

group = GROUP
version = VERSION_NAME

publishing {
    repositories {
        maven {
            val releasesRepoUrl = uri(System.getenv("releaseRepositoryUrl") ?: "")
            val snapshotsRepoUrl = uri(System.getenv("snapshotRepositoryUrl") ?: "")
            url = if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl

            credentials(PasswordCredentials::class) {
                username = System.getenv("NEXUS_USERNAME")
                password = System.getenv("NEXUS_PASSWORD")
            }
        }
    }

    publications.withType<MavenPublication> {

        artifact(tasks.register("${name}JavadocJar", Jar::class) {
            archiveClassifier.set("javadoc")
            archiveAppendix.set(this@withType.name)
        })

        pom {
            name = "MapComposeMP"
            description =
                "A Compose Multiplatform library to display tiled maps, with support for markers, paths, and rotation"
            url = "https://github.com/p-lr/MapComposeMP"
            inceptionYear = "2024"

            licenses {
                license {
                    name = "The Apache Software License, Version 2.0"
                    url = "http://www.apache.org/licenses/LICENSE-2.0.txt"
                }
            }
            developers {
                developer {
                    id = "p-lr"
                    name = "Pierre Laurence"
                    email = "https://github.com/p-lr/"
                }
            }
            scm {
                connection = "scm:git@github.com:p-lr/MapComposeMP.git"
                developerConnection = "scm:git@github.com:p-lr/MapComposeMP.git"
                url = "https://github.com/p-lr/MapComposeMP"
            }
        }
    }
}
ext["signing.keyId"] = System.getenv("signingKeyId")
ext["signing.password"] = System.getenv("signingPwd")
ext["signing.secretKeyRingFile"] = System.getenv("signingKeyFile")

signing {
    sign(publishing.publications)
}
