import java.security.MessageDigest

// KloudMate Java distro: an extension jar for the stock upstream OpenTelemetry Java agent.
// Everything OpenTelemetry is compileOnly: the agent provides it at runtime, so the jar
// carries only KloudMate classes and service files.
plugins {
    `java-library`
}

group = "com.kloudmate.otel"
version = providers.gradleProperty("distroVersion").getOrElse("0.2.0")

// Upstream versions this release layers on. Keep in step with JAVA_AGENT_VERSION in
// scripts/otel-agent-bundles/build.sh; the SDK version is the one that agent bundles.
val agentVersion = "2.34.0"
val sdkVersion = "1.68.0"

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

// kloudmate-javaagent.jar: the upstream agent with this extension embedded (upstream loads extensions/ from its own
// jar) and an entry class that passes the agent arguments, a KloudMate properties file, to the extension. The one
// jar to load at runtime attach, where no -Dotel.javaagent.extensions can be added.
val attach by sourceSets.creating
val upstreamAgent by configurations.creating { isTransitive = false }
dependencies { upstreamAgent("io.opentelemetry.javaagent:opentelemetry-javaagent:$agentVersion") }

val agentJar by tasks.registering(Jar::class) {
    group = "build"
    description = "The upstream agent with the KloudMate extension embedded"
    archiveFileName.set("kloudmate-javaagent.jar")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    duplicatesStrategy = DuplicatesStrategy.FAIL
    val upstream = upstreamAgent.elements.map { it.single().asFile }
    // The pinned sha256 in km-manifest.json: the repackaged jar is built only from the agent it names.
    val pinned = (groovy.json.JsonSlurper().parse(file("km-manifest.json")) as Map<*, *>)["artifacts"]
        .let { it as List<*> }.map { it as Map<*, *> }.single { it["path"] == "opentelemetry-javaagent.jar" }["sha256"] as String
    inputs.property("pinnedSha256", pinned)
    doFirst {
        val got = MessageDigest.getInstance("SHA-256").digest(upstream.get().readBytes())
            .joinToString("") { b -> "%02x".format(b) }
        check(got == pinned) { "opentelemetry-javaagent $agentVersion sha256 $got, km-manifest.json pins $pinned" }
    }
    from(upstream.map { zipTree(it) }) { exclude("META-INF/MANIFEST.MF") }
    from(attach.output)
    into("extensions") { from(tasks.jar) }
    manifest {
        attributes(
            "Implementation-Title" to "kloudmate-javaagent",
            "Implementation-Version" to project.version,
            "Implementation-Vendor" to "KloudMate",
            "KloudMate-Agent-Version" to agentVersion,
            "Main-Class" to "com.kloudmate.otel.attach.KmAgent",
            "Premain-Class" to "com.kloudmate.otel.attach.KmAgent",
            "Agent-Class" to "com.kloudmate.otel.attach.KmAgent",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true",
        )
    }
}
tasks.assemble { dependsOn(agentJar) }
