plugins {
    `maven-publish`
    id("java")
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "ru.raveon"
version = "2.1.0"

repositories {
    mavenCentral()
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
}

dependencies {
    testImplementation("org.xerial:sqlite-jdbc:3.49.1.0")
    testImplementation("io.papermc.paper:paper-api:1.20.1-R0.1-SNAPSHOT")
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
    compileOnly("org.geysermc.cumulus:cumulus:1.1.2") { isTransitive = false }
    compileOnly(libs.floodgate.api) { isTransitive = false }
    compileOnly("com.mojang:authlib:1.5.21") { isTransitive = false }

    implementation("com.google.flatbuffers:flatbuffers-java:25.2.10")
    implementation("io.lettuce:lettuce-core:7.4.0.RELEASE")
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("org.slf4j:slf4j-jdk14:2.0.17")

    implementation(libs.relocations)
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
        includeEmptyDirs = false
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
        relocate("com.zaxxer.hikari", "ru.raveon.shade.hikari")
        relocate("org.slf4j", "ru.raveon.shade.slf4j")

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

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
}

val queueRegressionTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Checks packet queue recovery and stale scheduler callback isolation."
    dependsOn(tasks.testClasses)
    classpath = files(sourceSets["test"].output, sourceSets["main"].output)
    mainClass.set("ru.raveon.manager.anticheat.PacketWorkQueueRegression")
}

tasks.check {
    dependsOn(queueRegressionTest)
}

val databaseRegressionTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Checks pooled database connections, transactions and shutdown."
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("ru.raveon.api.database.DatabaseManagerRegression")
}

tasks.check {
    dependsOn(databaseRegressionTest)
}

val databaseJarSmokeTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Checks pooled SQL and isolated logging in the packaged plugin."
    dependsOn(tasks.shadowJar, tasks.testClasses)
    classpath = files(tasks.shadowJar.flatMap { it.archiveFile }, sourceSets["test"].output) +
            configurations.testRuntimeClasspath.get().filter { !it.name.startsWith("HikariCP-") }
    mainClass.set("ru.raveon.api.database.ShadedDatabaseSmoke")
}

tasks.check {
    dependsOn(databaseJarSmokeTest)
}

tasks.register<JavaExec>("databaseLoadTest") {
    group = "verification"
    description = "Runs concurrent SQL, overload and shutdown stress scenarios."
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("ru.raveon.api.database.DatabaseLoadTest")
    args(layout.buildDirectory.dir("reports/database-load").get().asFile.absolutePath)
}

val configurationRegressionTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Checks English-only configuration and migration from catalog references."
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("ru.raveon.config.ConfigurationRegression")
}

tasks.check {
    dependsOn(configurationRegressionTest)
}
