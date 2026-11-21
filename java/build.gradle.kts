// KloudMate Java distro: an extension jar for the stock upstream OpenTelemetry Java agent.
// Everything OpenTelemetry is compileOnly: the agent provides it at runtime, so the jar
// carries only KloudMate classes and service files.
plugins {
    `java-library`
}

group = "com.kloudmate.otel"
version = providers.gradleProperty("distroVersion").getOrElse("0.1.1")

// Upstream versions this release layers on. Keep in step with JAVA_AGENT_VERSION in
// scripts/otel-agent-bundles/build.sh; the SDK version is the one that agent bundles.
val agentVersion = "2.29.0"
val sdkVersion = "1.63.0"

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

repositories { mavenCentral() }

dependencies {
    compileOnly(platform("io.opentelemetry:opentelemetry-bom:$sdkVersion"))
    compileOnly(platform("io.opentelemetry:opentelemetry-bom-alpha:$sdkVersion-alpha"))
    compileOnly("io.opentelemetry:opentelemetry-sdk")
    compileOnly("io.opentelemetry:opentelemetry-sdk-extension-autoconfigure-spi")
    compileOnly("io.opentelemetry:opentelemetry-sdk-extension-declarative-config")

    testImplementation(platform("io.opentelemetry:opentelemetry-bom:$sdkVersion"))
    testImplementation(platform("io.opentelemetry:opentelemetry-bom-alpha:$sdkVersion-alpha"))
    testImplementation("io.opentelemetry:opentelemetry-sdk")
    testImplementation("io.opentelemetry:opentelemetry-sdk-extension-autoconfigure-spi")
    testImplementation("io.opentelemetry:opentelemetry-sdk-extension-declarative-config")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
    options.compilerArgs.addAll(listOf("-Xlint:all,-options,-processing", "-Werror"))
}

// distro.properties carries the release version for telemetry.distro.version.
val genProps by tasks.registering {
    val out = layout.buildDirectory.dir("generated/resources/distro")
    val v = project.version.toString()
    inputs.property("version", v)
    inputs.property("agentVersion", agentVersion)
    outputs.dir(out)
    doLast {
        val f = out.get().file("com/kloudmate/otel/distro.properties").asFile
        f.parentFile.mkdirs()
        f.writeText("version=$v\nagent.version=$agentVersion\n")
    }
}
sourceSets.main { resources.srcDir(genProps) }

tasks.jar {
    archiveFileName.set("kloudmate-otel-extension.jar")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    manifest {
        attributes(
            "Implementation-Title" to "kloudmate-java",
            "Implementation-Version" to project.version,
            "Implementation-Vendor" to "KloudMate",
            "KloudMate-Agent-Version" to agentVersion,
        )
    }
}

tasks.test { useJUnitPlatform() }
