plugins {
    java
    id("com.gradleup.shadow") version "8.3.5"
}

group = "dev.realcoords"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.opencollab.dev/main/")
    maven("https://repo.opencollab.dev/maven-snapshots/")
}

dependencies {
    implementation("org.ow2.asm:asm:9.7.1")

    testImplementation("org.geysermc.mcprotocollib:protocol:26.3")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.jar {
    manifest {
        attributes(
            "Premain-Class" to "dev.realcoords.agent.RealCoordsAgent",
            "Can-Redefine-Classes" to "false",
            "Can-Retransform-Classes" to "false"
        )
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    manifest {
        attributes(
            "Premain-Class" to "dev.realcoords.agent.RealCoordsAgent",
            "Can-Redefine-Classes" to "false",
            "Can-Retransform-Classes" to "false"
        )
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
