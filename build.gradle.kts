import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
}

group = "com.nearfuturevr"
version = "1.0.0"

kotlin {
    jvmToolchain(17)
}

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    // Compose Desktop runtime
    implementation(compose.desktop.currentOs)

    // لازم Material3 عشان imports حقك
    implementation(compose.material3)

    // (اختياري لكن عملي) لو تستخدم أجزاء material/foundation بشكل مباشر
    implementation(compose.material)
    implementation(compose.foundation)

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}

compose.desktop {
    application {
        mainClass = "MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Dmg)

            packageName = "NFVR_Quest_Installer"
            description = "Near FutureVR - مثبت ألعاب Meta Quest"
            vendor = "Near FutureVR"
            packageVersion = "1.0.0"

            windows {
                iconFile.set(project.file("NFVR_Quest_Installer.ico"))
                menuGroup = "Near FutureVR"
                shortcut = true
            }

            macOS {
                iconFile.set(project.file("NFVR_Quest_Installer.icns"))
            }
        }
    }
}
