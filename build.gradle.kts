import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.vanniktech.maven.publish)
    alias(libs.plugins.kotlinx.kover)
}

group = "io.zenwave360.jsonrefparser"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
    mavenLocal()
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    js(IR) {
        nodejs()
        binaries.executable()
        useEsModules()
        compilations["main"].packageJson {
            customField("name", "@zenwave360/asyncapi-parser-kmp")
            customField("description", "Lightweight AsyncAPI Parser for Kotlin Multiplatform")
            customField("license", "MIT")
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                // TODO: repin to the real Maven Central version once json-schema-ref-parser-kmp is released
                implementation(libs.json.schema.ref.parser.kmp)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        val jvmTest by getting
        val jsMain by getting
        val jsTest by getting
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    compilerOptions.freeCompilerArgs.add("-Xexpect-actual-classes")
}

tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

val hasSigningCredentials = sequenceOf(
    "signingInMemoryKey",
    "signingKey",
    "signing.secretKeyRingFile",
).any { !providers.gradleProperty(it).orNull.isNullOrBlank() }

// Local staging repository used by the release workflow: the build job publishes
// here with NO credentials (task: publishAllPublicationsToLocalStagingRepository),
// and a separate privileged job signs and uploads the result to the Central
// Portal without executing any Gradle code. See docs/release-security.md.
publishing {
    repositories {
        maven {
            name = "localStaging"
            url = uri(layout.buildDirectory.dir("staging-deploy"))
        }
    }
}

mavenPublishing {
    publishToMavenCentral()
    if (hasSigningCredentials) signAllPublications()
    pom {
        name.set("AsyncAPI Parser KMP")
        description.set("AsyncAPI v2/v3 traits processor and semantic navigator for Kotlin Multiplatform")
        url.set("https://github.com/ZenWave360/asyncapi-parser-kmp")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }
        developers {
            developer {
                id.set("ivangsa")
                name.set("Ivan Garcia Sainz-Aja")
                email.set("ivangsa@gmail.com")
            }
        }
        scm {
            connection.set("scm:git:git://github.com/ZenWave360/asyncapi-parser-kmp.git")
            developerConnection.set("scm:git:ssh://github.com/ZenWave360/asyncapi-parser-kmp.git")
            url.set("https://github.com/ZenWave360/asyncapi-parser-kmp")
        }
    }
}
