plugins {
    `maven-publish`
    id("java")
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "ru.raveon"
version = "2.0.4"

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

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
}
