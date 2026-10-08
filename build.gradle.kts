plugins {
    kotlin("jvm") version "2.4.20"
    id("org.jetbrains.compose") version "1.12.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
}

group = "dev.hereliesaz.burningibridge"
version = "0.1.0"

kotlin { jvmToolchain(21) }

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("com.github.mwiede:jsch:0.2.26")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.apache.commons:commons-compress:1.28.0")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.13.4")
}

compose.desktop {
    application {
        mainClass = "dev.hereliesaz.burningibridge.MainKt"
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb
            )
            packageName = "Burning-iBridge"
            packageVersion = "1.1.0"
            description = "T2 bridgeOS research workstation"
            vendor = "HereLiesAz"
        }
    }
}

tasks.test { useJUnitPlatform() }
