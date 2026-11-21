import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing: key.properties (local, gitignored) or KEYSTORE_PATH/KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD
// (CI with secrets). With neither, a release build is unsigned and can't be installed.
val keyProps = Properties().apply {
    val f = rootProject.file("key.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(env: String, prop: String): String? = System.getenv(env)?.takeIf { it.isNotEmpty() } ?: keyProps.getProperty(prop)

android {
    namespace = "llm.slop.slopbox"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    signingConfigs {
        create("release") {
            val store = signingValue("KEYSTORE_PATH", "storeFile")
            if (store != null && file(store).exists()) {
                storeFile = file(store)
                storePassword = signingValue("KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("KEY_PASSWORD", "keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "llm.slop.slopbox"
        // 28: AAudio with usage/content type (Android 9).
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1"
        // ./gradlew assembleDebug -PappIdSuffix=.ui installs next to the main
        // build, so two lines of work (or a release check) can share one phone.
        (project.findProperty("appIdSuffix") as String?)?.let { applicationIdSuffix = it }

        ndk {
            // Must match what android-native/build_rockbox.sh built.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=none")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            // Codecs are dlopen()ed by name from the APK; keep them as-is.
            keepDebugSymbols += "**/libsb_*.so"
        }
    }
}

// Rockbox's own database menus and its credits list, shipped as assets straight
// from the submodule; and SlopBox's licence, for the About screen.
val rockboxAssets = layout.buildDirectory.dir("generated/rockboxAssets")
val copyRockboxAssets by tasks.registering(Copy::class) {
    from(rootProject.file("third_party/rockbox/apps/tagnavi.config"))
    from(rootProject.file("third_party/rockbox/docs/CREDITS"))
    from(rootProject.file("third_party/rockbox/docs/LICENSES"))
    from(rootProject.file("LICENSE"))
    into(rockboxAssets.map { it.dir("rockbox") })
}
android.sourceSets["main"].assets.srcDir(rockboxAssets)
tasks.named("preBuild") { dependsOn(copyRockboxAssets) }

// Rockbox's codepage tables, made by its own tools/codepages (Default Codepage).
val codepageAssets = layout.buildDirectory.dir("generated/codepageAssets")
val makeCodepages by tasks.registering(Exec::class) {
    val out = codepageAssets.map { it.dir("codepages") }
    inputs.file(rootProject.file("third_party/rockbox/tools/codepages.c"))
    inputs.file(rootProject.file("third_party/rockbox/tools/codepage_tables.c"))
    outputs.dir(out)
    commandLine(rootProject.file("android-native/make_codepages.sh").absolutePath, out.get().asFile.absolutePath)
}
android.sourceSets["main"].assets.srcDir(codepageAssets)
tasks.named("preBuild") { dependsOn(makeCodepages) }

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.media3.session)
    implementation(libs.media3.common)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
