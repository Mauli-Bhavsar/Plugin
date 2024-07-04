plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.23"
    id("org.jetbrains.intellij") version "1.17.3"
}

group = "plugin-dev"
version = "0.0.1"

repositories {
    mavenCentral()
}

intellij {
    version.set("2023.2.6")
    type.set("IC") // Target IDE Platform

    plugins.set(listOf("java"))
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }

    patchPluginXml {
        sinceBuild.set("232")
        untilBuild.set("242.*")
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token = System.getenv("PUBLISH_TOKEN")
    }

    register("printToken") {
        doLast {
            println("Publish Token: ${System.getenv("PUBLISH_TOKEN") ?: "Not Set"}")
        }
    }

    register<Jar>("createPluginJar") {
        archiveBaseName.set("Bean detection error version-7.1")
        archiveVersion.set("0.0.1")

        from(sourceSets.main.get().output)
        dependsOn(configurations.runtimeClasspath)

        from({
            configurations.runtimeClasspath.get().map {
                if (it.isDirectory) it
                else zipTree(it)
            }
        })

        manifest {
            attributes["Main-Class"] = "com.your.MainClass"
            // This is not usually needed for an IntelliJ plugin
        }

        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
}
