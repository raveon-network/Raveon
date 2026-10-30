plugins {
    `maven-publish`
    id("java")
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "ru.raveon"
version = "2.0.1"

repositories {
    exclusiveContent {
        forRepository {
            maven { url = uri("https://maven.enginehub.org/repo/") }
        }
        filter {
            includeGroup("com.sk89q.worldedit")
            includeGroup("com.sk89q.worldguard")
        }
    }
    maven { url = uri("https://jitpack.io") }
    maven { url = uri("https://repo.papermc.io/repository/maven-public/") }
    maven { url = uri("https://oss.sonatype.org/content/groups/public/") }
    maven { url = uri("https://repo.codemc.io/repository/maven-releases/") }
    maven { url = uri("https://repo.codemc.io/repository/maven-snapshots/") }
    maven { url = uri("https://repo.opencollab.dev/main/") }
    maven { url = uri("https://repo.opencollab.dev/maven-snapshots/") }
    maven { url = uri("https://repo.opencollab.dev/maven-releases/") }
    maven { url = uri("https://libraries.minecraft.net/") }
    mavenCentral()
}

val legacyTestApi by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
val currentTestApi by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
val modernTestApi by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.20.1-R0.1-SNAPSHOT")

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    compileOnly("com.github.retrooper:packetevents-spigot:2.12.1")
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.5") {
        isTransitive = false
    }
    compileOnly("com.sk89q.worldguard:worldguard-core:7.0.5") {
        isTransitive = false
    }
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.2.0") {
        isTransitive = false
    }
    compileOnly("com.sk89q.worldedit:worldedit-core:7.2.0") {
        isTransitive = false
    }

    compileOnly(libs.geyser.api) { isTransitive = false }
    compileOnly("org.geysermc.api:base-api:1.0.2") { isTransitive = false }
    compileOnly(libs.floodgate.api) { isTransitive = false }
    compileOnly("com.mojang:authlib:1.5.21") { isTransitive = false }

    implementation("com.google.flatbuffers:flatbuffers-java:25.2.10")
    implementation("io.lettuce:lettuce-core:7.4.0.RELEASE")

    implementation(libs.relocations)
    testRuntimeOnly("org.xerial:sqlite-jdbc:3.51.1.0")
    testImplementation("org.jspecify:jspecify:1.0.0")
    testRuntimeOnly("commons-lang:commons-lang:2.6")
    testRuntimeOnly("net.kyori:adventure-text-minimessage:4.26.1")
    legacyTestApi("com.destroystokyo.paper:paper-api:1.16.5-R0.1-SNAPSHOT")
    legacyTestApi("org.yaml:snakeyaml:1.27")
    currentTestApi("io.papermc.paper:paper-api:1.20.1-R0.1-SNAPSHOT")
    currentTestApi("org.yaml:snakeyaml:2.0")
    modernTestApi("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    modernTestApi("org.yaml:snakeyaml:2.2")
    compileOnly(fileTree("libs") {
        include("*.jar")
    })
}

tasks {
    jar {
        enabled = false
    }

    shadowJar {
        archiveClassifier.set("")
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true

        relocate("com.fasterxml.jackson", "ru.raveon.shade.jackson")
        //relocate("com.github.retrooper.packetevents", "ru.raveon.libs.packetevents")
        relocate("okhttp3", "ru.raveon.shade.okhttp3")
        relocate("okio", "ru.raveon.shade.okio")
        relocate("kotlin", "ru.raveon.shade.kotlin")
        relocate("org.reflections", "ru.raveon.shade.reflections")
        relocate("javassist", "ru.raveon.shade.javassist")

        relocate("io.lettuce", "ru.raveon.shade.lettuce")
        relocate("io.netty", "ru.raveon.shade.netty")

        exclude("META-INF/*.SF")
        exclude("META-INF/*.DSA")
        exclude("META-INF/*.RSA")
        exclude("META-INF/maven/**")
        exclude("META-INF/versions/**")

        mergeServiceFiles()

        manifest {
            attributes(
                "Implementation-Version" to project.version
            )
        }
    }

    build {
        dependsOn(shadowJar)
    }

    processResources {
        val props = mapOf("version" to version)
        inputs.properties(props)
        filteringCharset = "UTF-8"

        filesMatching("plugin.yml") {
            expand(props)
        }
    }
    clean {
        delete(fileTree("build/libs") {
            include("*.jar")
        })
    }
}

java {
    val targetJavaVersion = 17
    val javaVersion = JavaVersion.toVersion(targetJavaVersion)
    sourceCompatibility = javaVersion
    targetCompatibility = javaVersion

    if (JavaVersion.current() < javaVersion) {
        toolchain.languageVersion = JavaLanguageVersion.of(targetJavaVersion)
    }
}


publishing {
    publications {
        create<MavenPublication>("shadow") {
            artifact(tasks.shadowJar.get())
            artifactId = project.name
            groupId = project.group.toString()
            version = project.version.toString()
        }
    }
}

artifacts {
    add("default", tasks.shadowJar)
}

configurations.testCompileOnly {
    extendsFrom(configurations.compileOnly.get())
}
tasks.register<JavaExec>("verifyPackagedDual") {
    dependsOn(tasks.testClasses, tasks.shadowJar)
    classpath = sourceSets.test.get().output + files(tasks.shadowJar.get().archiveFile) +
            sourceSets.main.get().compileClasspath + sourceSets.test.get().runtimeClasspath.filter {
                it !in sourceSets.main.get().output.files
            }
    mainClass.set("ru.raveon.verification.DualModelVerification")
    systemProperty("verify.packaged", "true")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
}

val regressionChecks = mapOf(
    "verifyTelemetry" to "ru.raveon.verification.TelemetryVerification",
    "verifyMultimodelIntegration" to "ru.raveon.verification.MultimodelIntegrationVerification",
    "verifyMultimodel" to "ru.raveon.verification.MultimodelVerification",
    "verifyPunishments" to "ru.raveon.verification.PunishmentVerification",
    "verifyLanguage" to "ru.raveon.verification.LanguageVerification",
    "verifyDual" to "ru.raveon.verification.DualModelVerification",
    "verifyPacketQueue" to "ru.raveon.verification.PacketQueueVerification",
    "verifyUltraFeatures" to "ru.raveon.verification.UltraFeatureVerification",
    "verifyDatabase" to "ru.raveon.verification.DatabaseRegressionVerification",
    "verifyHttpLimits" to "ru.raveon.service.analyze.HttpLimitVerification",
    "verifyDatasets" to "ru.raveon.verification.DatasetVerification",
    "verifyConfiguration" to "ru.raveon.verification.ConfigurationVerification",
    "verifyUiLifecycle" to "ru.raveon.verification.UiLifecycleVerification",
    "verifyMonitorCommands" to "ru.raveon.verification.MonitorCommandVerification",
    "verifyTargetIndex" to "ru.raveon.verification.TargetIndexVerification"
)
regressionChecks.forEach { (taskName, entryPoint) ->
    tasks.register<JavaExec>(taskName) {
        dependsOn(tasks.testClasses)
        classpath = sourceSets.test.get().runtimeClasspath + sourceSets.main.get().compileClasspath
        mainClass.set(entryPoint)
        val sqliteNative = layout.buildDirectory.dir("sqlite-native").get().asFile
        systemProperty("org.sqlite.tmpdir", sqliteNative.absolutePath)
        doFirst { sqliteNative.mkdirs() }
    }
}
fun registerApiVerification(
    taskName: String,
    entryPoint: String,
    api: org.gradle.api.artifacts.Configuration,
    argument: String,
    modern: Boolean = false
) {
    tasks.register<JavaExec>(taskName) {
        dependsOn(tasks.testClasses)
        classpath = api + (sourceSets.test.get().runtimeClasspath +
                sourceSets.main.get().compileClasspath).filter {
                    !it.name.startsWith("paper-api-") && !it.name.startsWith("snakeyaml-")
                }
        mainClass.set(entryPoint)
        args(argument)
        if (modern) {
            javaLauncher.set(javaToolchains.launcherFor {
                languageVersion.set(JavaLanguageVersion.of(21))
            })
        }
    }
}

registerApiVerification("verifyHologramLegacy",
    "ru.raveon.verification.HologramPacketVerification", legacyTestApi, "1.16.5")
registerApiVerification("verifyHologram1201",
    "ru.raveon.verification.HologramPacketVerification", currentTestApi, "1.20.1")
registerApiVerification("verifyHologram12111",
    "ru.raveon.verification.HologramPacketVerification", modernTestApi, "1.21.11", true)
registerApiVerification("verifySchedulerLegacy",
    "ru.raveon.verification.SchedulerApiVerification", legacyTestApi, "legacy")
registerApiVerification("verifySchedulerModern",
    "ru.raveon.verification.SchedulerApiVerification", modernTestApi, "modern", true)

registerApiVerification("verifyLanguageLegacy",
    "ru.raveon.verification.LanguageVerification", legacyTestApi, "1.16.5")
registerApiVerification("verifyLanguageModern",
    "ru.raveon.verification.LanguageVerification", modernTestApi, "1.21.11", true)

tasks.register<JavaExec>("verifyRedisPresence") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath + sourceSets.main.get().compileClasspath
    mainClass.set("ru.raveon.verification.RedisPresenceVerification")
}

registerApiVerification("verifyTelemetryLegacy",
    "ru.raveon.verification.TelemetryVerification", legacyTestApi, "legacy")
registerApiVerification("verifyTelemetryModern",
    "ru.raveon.verification.TelemetryVerification", modernTestApi, "modern", true)

tasks.check {
    dependsOn(regressionChecks.keys, "verifyPackagedDual", "verifyTelemetryLegacy", "verifyTelemetryModern",
        "verifyHologramLegacy", "verifyHologram1201", "verifyHologram12111",
        "verifySchedulerLegacy", "verifySchedulerModern", "verifyLanguageLegacy", "verifyLanguageModern")
}
