import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    android {
        namespace = "ru.ymlstudio.shared"
        compileSdk = 36
        minSdk = 26
        withHostTestBuilder {}.configure {}
    }
    jvm("desktop")
    jvmToolchain(17)
    sourceSets {
        androidMain { kotlin.srcDir("src/jvmMain/kotlin") }
        named("desktopMain") { kotlin.srcDir("src/jvmMain/kotlin") }
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(compose.material3)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
            }
        }
        val desktopTest by getting {
            dependencies { implementation(compose.desktop.uiTestJUnit4) }
        }
    }
}

compose.desktop {
    application {
        // Android Studio's runtime may omit jpackage. Allow a full JDK for installers.
        javaHome = providers.gradleProperty("desktopJavaHome").orElse(System.getProperty("java.home")).get()
        mainClass = "ru.ymlstudio.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Exe)
            packageName = "YMLStudio"
            packageVersion = "2.2.2"
            // WiX's default MSI code page cannot encode Cyrillic metadata (LGHT0311).
            description = "YML Studio - catalog editor"
            vendor = "YML Studio"
            modules("java.desktop", "java.logging", "java.xml", "jdk.unsupported")
            macOS { bundleID = "ru.ymlstudio.desktop" }
            windows {
                menuGroup = "YML Studio"
                shortcut = true
                menu = true
                perUserInstall = true
                upgradeUuid = "8f8b54b3-34ed-4bf2-962e-77e1a639a053"
            }
        }
    }
}
