plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("com.phloots.jam.levelgen.MainKt")
}

tasks.named<JavaExec>("run") {
    // Run relative to the repo root so default output paths (app/src/main/assets) resolve.
    workingDir = rootProject.projectDir
}

tasks.test {
    useJUnitPlatform()
}

// Reverse ("director") generation preview / diagnostics (reverse level designer).
tasks.register<JavaExec>("reversePreview") {
    group = "application"
    description = "Reverse (director) generator preview: levelgen/out/reverse-preview-levels.json"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(providers.gradleProperty("reverseMain").orElse("com.phloots.jam.levelgen.reverse.ReversePreviewKt"))
    workingDir = rootProject.projectDir
    jvmArgs("-Xss16m", "-Xmx4g")
}
