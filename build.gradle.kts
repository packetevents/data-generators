import org.spongepowered.gradle.vanilla.repository.MinecraftPlatform

plugins {
    id("java-library")
    org.spongepowered.gradle.vanilla
}

group = "dev.booky"
version = "1.0.0"

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
        vendor = JvmVendorSpec.ADOPTIUM
    }
}

minecraft {
    version(project.ext["mcVersion"] as String)
    platform(MinecraftPlatform.SERVER)

    runs {
        server()
        server("generate") {
            mainClass("dev.booky.generation.GenerationMain")
            accessWideners(sourceSets.main.map { it.resources.single { file -> file.name == "generators.accesswidener" } })
            args(project.layout.projectDirectory.dir("generated").toString())
        }
    }
}
