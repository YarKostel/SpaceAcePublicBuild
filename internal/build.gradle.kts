import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Плоская раскладка, как в Go: пакет = папка прямо в модуле (корень com.spaceace.game опущен), тесты *Test.kt
// лежат рядом с кодом. Скрипт сборки и build/ в исходники не попадают.
sourceSets {
    main {
        kotlin.setSrcDirs(listOf("."))
        kotlin.include("**/*.kt")
        kotlin.exclude("build/**", "**/*Test.kt")
    }
    test {
        kotlin.setSrcDirs(listOf("."))
        kotlin.include("**/*Test.kt")
        kotlin.exclude("build/**")
    }
}

dependencies {
    api(libs.gdx.core)
    api(libs.gdx.box2d)
    // Чёткий текст: буквы растеризуются из .ttf под реальный размер в пикселях экрана.
    api(libs.gdx.freetype)
    api(libs.ktx.app)
    api(libs.ktx.assets)
    api(libs.ktx.box2d)
    api(libs.ktx.graphics)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    // Нативный Box2D для интеграционного теста полного забега (без окна и OpenGL).
    testImplementation("com.badlogicgames.gdx:gdx-box2d-platform:${libs.versions.gdx.get()}:natives-desktop")
}

tasks.test {
    useJUnitPlatform()
}
