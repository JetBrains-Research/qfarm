import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.time.LocalDate
import java.time.format.DateTimeFormatter


plugins {
    kotlin("jvm") version "1.9.24"
    kotlin("plugin.serialization") version "1.9.24"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("me.champeau.jmh") version "0.7.2"
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
    testImplementation(kotlin("test-junit5"))

    implementation("io.jenetics:jenetics:7.2.0")
    implementation("io.jenetics:jenetics.ext:7.2.0")
    implementation("com.github.ajalt.clikt:clikt:4.2.0")
    implementation("org.knowm.xchart:xchart:3.8.8")
    implementation("org.jetbrains.lets-plot:lets-plot-kotlin-jvm:4.11.1")
    runtimeOnly("org.jetbrains.lets-plot:lets-plot-image-export:4.7.2")
    implementation("org.slf4j:slf4j-nop:2.0.13")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("org.apache.commons:commons-text:1.11.0")
    implementation("tech.tablesaw:tablesaw-core:0.43.1")
    implementation("org.json:json:20240303")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    jmh("org.openjdk.jmh:jmh-core:1.37")
    jmh("org.openjdk.jmh:jmh-generator-annprocess:1.37")

}

tasks.shadowJar {
    archiveBaseName.set("qfarm")
    archiveClassifier.set("")
    archiveVersion.set("$version.${project.findProperty("buildCounter") ?: "0"}")

    mergeServiceFiles()

    manifest {
        attributes(
            "Main-Class" to "org.jetbrains.bio.qfarm.MainKt"
        )
    }
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
