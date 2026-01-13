import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinJvmCompilation
import java.time.LocalDate
import java.time.format.DateTimeFormatter


plugins {
    kotlin("jvm") version "1.9.24"
    kotlin("plugin.serialization") version "1.9.24"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

// QFARM application version
version = "0.1"
group = "org.jetbrains.bio"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(kotlin("test"))

    implementation("net.sf.jopt-simple:jopt-simple:5.0.4")
    implementation("io.jenetics:jenetics:7.2.0")
    implementation("io.jenetics:jenetics.ext:6.3.0")
    implementation("org.knowm.xchart:xchart:3.8.8")
    implementation("org.jetbrains.lets-plot:lets-plot-kotlin-jvm:4.11.1")
    runtimeOnly("org.jetbrains.lets-plot:lets-plot-image-export:4.7.2")
    implementation("org.slf4j:slf4j-nop:2.0.13")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.lets-plot:lets-plot-kotlin:4.9.0")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("org.apache.commons:commons-text:1.11.0")
    implementation("tech.tablesaw:tablesaw-core:0.43.1")
}

tasks.jar {
    manifest {
        attributes(
            "Main-Class" to "org.jetbrains.bio.qfarm.Main"
            // To use a package, adjust with: "org.example.MainKt"
        )
    }
}

tasks.shadowJar {
    archiveBaseName.set("qfarm")
    archiveClassifier.set("") // no "-all"
    archiveVersion.set("$version.${project.findProperty("buildCounter") ?: "0"}") // no version in filename
    mergeServiceFiles() // good for Lets-Plot, Jenetics
}

// Process build properties task
tasks.register<Copy>("processBuildProperties") {
    dependsOn(tasks.shadowJar)

    // Turn off cache for this task
    outputs.upToDateWhen { false }

    // Define tokens for replacement
    val tokens = mapOf(
        "VERSION" to version.toString(),
        "BUILD" to (project.findProperty("buildCounter") ?: "0").toString(),
        "DATE" to LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM dd, yyyy"))
    )

    from(sourceSets.main.get().resources) {
        include("qfarm.properties")
        // Replace tokens in the file content using a Kotlin-compatible solution
        filesMatching("qfarm.properties") {
            expand(tokens)
        }
    }
    sourceSets.main.get().output.resourcesDir?.let { into(it) }
}


tasks.jar {
    dependsOn("processBuildProperties")

    manifest {
        attributes(
            "provider" to "gradle",
            "Application-Name" to "Qfarm $version",
            "Built-By" to "JetBrains Research TeamCity"
        )
    }
}

tasks.test {
    useJUnitPlatform()
}

val compileKotlin: KotlinCompile by tasks
compileKotlin.compilerOptions {
    freeCompilerArgs.set(
        listOf("-XXLanguage:+BreakContinueInInlineLambdas")
    )
    jvmTarget.set(JvmTarget.JVM_21)
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
