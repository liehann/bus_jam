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
    mainClass.set("com.phloots.busjam.levelgen.MainKt")
}

tasks.named<JavaExec>("run") {
    // Run relative to the repo root so default output paths (app/src/main/assets) resolve.
    workingDir = rootProject.projectDir
}

tasks.test {
    useJUnitPlatform()
}
