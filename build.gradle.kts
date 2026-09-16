import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
}

group = "com.nearfuturevr"
version = "2.3.3"

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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.json:json:20240303")
    // Direct Windows IFileOpenDialog adapter (BSD/Apache-2.0 dual license).
    implementation("net.java.dev.jna:jna:5.17.0")
    implementation("net.java.dev.jna:jna-platform:5.17.0")

    testImplementation(kotlin("test"))
}

tasks.processResources {
    filesMatching("app.properties") {
        expand("version" to project.version.toString())
    }
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Dmg)

            packageName = "NFVR_Quest_Installer"
            description = "Near FutureVR - مثبت ألعاب Meta Quest"
            vendor = "Near FutureVR"
            packageVersion = project.version.toString()

            windows {
                iconFile.set(project.file("NFVR_Quest_Installer.ico"))
                menuGroup = "Near FutureVR"
                shortcut = true
                upgradeUuid = "7cb46291-dd03-47c9-9be0-b06a73b3f132"
            }

            macOS {
                iconFile.set(project.file("NFVR_Quest_Installer.icns"))
            }
        }
    }
}
