plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

val composeApp = project(":demo:composeApp")
val composeResourcesPackage = "ovh.plrapps.mapcomposemp.demo"

// The JetBrains Compose Resources plugin doesn't wire its output directory when composeApp is a
// KMP library module (com.android.kotlin.multiplatform.library). Copy prepared resources — which
// include compiled .cvr value files — into this app's assets under the package-prefixed path that
// the Compose runtime expects.
val copyComposeResources = tasks.register<Sync>("copyComposeResourcesToAssets") {
    from(composeApp.layout.buildDirectory.dir(
        "generated/compose/resourceGenerator/preparedResources/commonMain/composeResources"
    ))
    into(layout.buildDirectory.dir(
        "generated/composeResources/composeResources/$composeResourcesPackage"
    ))
    dependsOn(":demo:composeApp:prepareComposeResourcesTaskForCommonMain")
}

val generatedComposeResources = layout.buildDirectory.dir("generated/composeResources").get().asFile

android {
    sourceSets["main"].assets.directories.add(generatedComposeResources.absolutePath)
    namespace = "ovh.plrapps.mapcomposemp.demo.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "ovh.plrapps.mapcomposemp.demo"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

afterEvaluate {
    tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
        dependsOn(copyComposeResources)
    }
}

dependencies {
    implementation(project(":demo:composeApp"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.voyager.navigation)
    debugImplementation(libs.compose.ui.tooling)
}
