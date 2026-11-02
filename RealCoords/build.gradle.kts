plugins {
    java
    id("com.gradleup.shadow") version "8.3.5"
}

group = "dev.realcoords"
version = "1.0.0"

val geyserVersion: String by project
val javaVersion: String by project

repositories {
    mavenCentral()
    maven("https://repo.opencollab.dev/main/")
    maven("https://repo.opencollab.dev/maven-snapshots/")
}

configurations.named("testImplementation") {
    extendsFrom(configurations.named("compileOnly").get())
}

dependencies {
    // Compile only against the official, unmodified Geyser API.
    compileOnly("org.geysermc.geyser:api:$geyserVersion")
    compileOnly("org.geysermc.api:base-api:1.0.3")
    compileOnly("org.cloudburstmc.math:immutable:2.0")
    compileOnly("org.jetbrains:annotations:24.0.0")
    compileOnly("org.geysermc.event:events:1.1-SNAPSHOT")
    compileOnly("org.geysermc.mcprotocollib:protocol:26.3")

    implementation("org.yaml:snakeyaml:2.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(javaVersion.toInt())
}

tasks.shadowJar {
    archiveClassifier.set("")
    relocate("org.yaml.snakeyaml", "dev.realcoords.libs.snakeyaml")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
