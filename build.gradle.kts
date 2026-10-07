plugins {
    kotlin("jvm") version "1.9.23"
    kotlin("plugin.serialization") version "1.9.23"
    id("org.jetbrains.compose") version "1.6.10"
}

val appVersion = file("version.txt").readText().trim()
group = "com.vaultpass"
version = appVersion

repositories {
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    google()
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.zxing:javase:3.5.3")
    
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "com.vaultpass.desktop.MainKt"
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg, org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi, org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb)
            packageName = "VaultPass"
            packageVersion = appVersion
            modules(
                "java.base",
                "java.desktop",
                "java.sql",
                "java.naming",
                "jdk.unsupported",
                "java.net.http",
                "java.xml",
                "java.management",
                "java.logging",
                // EC (P-256) for verifying signed updates. Listed explicitly so jlink never drops it.
                "jdk.crypto.ec"
            )
            windows {
                iconFile.set(project.file("assets/logo.ico"))
            }
        }
    }
}

afterEvaluate {
    tasks.named<JavaExec>("run") {
        systemProperty("vaultpass.env", "dev")
    }
}

tasks.named<ProcessResources>("processResources") {
    from("version.txt")
}
