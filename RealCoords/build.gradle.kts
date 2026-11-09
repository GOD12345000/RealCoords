plugins {
    java
    id("com.gradleup.shadow") version "8.3.5"
}

group = "dev.realcoords"
version = "2.0.0"

val geyserVersion: String by project
val javaVersion: String by project

repositories {
    mavenCentral()
    maven("https://repo.opencollab.dev/main/")
}

dependencies {
    // Geyser 與 Cloudburst Protocol / Netty / NBT 由 Geyser 在執行時提供
    compileOnly("org.geysermc.geyser:api:$geyserVersion")
    compileOnly("org.geysermc.geyser:core:$geyserVersion")
    implementation("org.yaml:snakeyaml:2.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.geysermc.geyser:core:$geyserVersion")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(javaVersion.toInt())
}

tasks.shadowJar {
    archiveClassifier.set("")
    relocate("org.yaml.snakeyaml", "dev.realcoords.libs.snakeyaml")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showCauses = true
        showExceptions = true
        showStackTraces = true
    }
}

tasks.build { dependsOn(tasks.shadowJar) }
