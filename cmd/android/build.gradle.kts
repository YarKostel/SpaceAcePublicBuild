import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val natives: Configuration by configurations.creating

val gdxVersion: String = libs.versions.gdx.get()
val androidAbis = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
val nativesDir: File = layout.buildDirectory.dir("gdx-natives").get().asFile

android {
    namespace = "com.spaceace.game"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.spaceace.game"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // Плоская раскладка: манифест и res/ в корне модуля, лаунчер — в src/ (не корень модуля, иначе в исходники
    // попали бы сгенерированные build/generated/*.java).
    sourceSets.getByName("main") {
        manifest.srcFile("AndroidManifest.xml")
        res.setSrcDirs(listOf("res"))
        kotlin.setSrcDirs(listOf("src"))
        assets.srcDir(rootProject.file("assets"))
        jniLibs.srcDir(nativesDir)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.gdx.backend.android)
    androidAbis.forEach { abi ->
        natives("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-$abi")
        natives("com.badlogicgames.gdx:gdx-box2d-platform:$gdxVersion:natives-$abi")
        natives("com.badlogicgames.gdx:gdx-freetype-platform:$gdxVersion:natives-$abi")
    }
}

// Нативные .so из natives-*.jar раскладываются по папкам ABI, которые AGP подхватывает как jniLibs.
val copyAndroidNatives by tasks.registering(Sync::class) {
    into(nativesDir)
    androidAbis.forEach { abi ->
        into(abi) {
            from(provider { natives.filter { it.name.endsWith("natives-$abi.jar") }.map { zipTree(it) } })
            include("*.so")
        }
    }
}

tasks.matching { it.name.contains("merge") && it.name.contains("JniLibFolders") }.configureEach {
    dependsOn(copyAndroidNatives)
}
