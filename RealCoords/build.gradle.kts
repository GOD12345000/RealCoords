plugins {
    java
    id("com.gradleup.shadow") version "8.3.5"
}

group = "dev.realcoords"
version = "1.0.0"

val geyserVersion: String by project
val javaVersion: String by project

repositories {
    // Allows building against a locally patched Geyser API/core installed with publishToMavenLocal.
    mavenLocal()
    mavenCentral()
    maven("https://repo.opencollab.dev/main/")
    maven("https://repo.opencollab.dev/maven-snapshots/")
}

dependencies {
    // Geyser 與 Cloudburst Protocol / Netty / NBT 由 Geyser 在執行時提供
    compileOnly("org.geysermc.geyser:api:$geyserVersion")
    compileOnly("org.geysermc.geyser:core:$geyserVersion")
    // Public types exposed by the patched DownstreamSessionInitializeEvent and packet hook.
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

tasks.build { dependsOn(tasks.shadowJar) }
