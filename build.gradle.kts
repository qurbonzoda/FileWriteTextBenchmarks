plugins {
    kotlin("jvm") version "1.9.0"
    id("org.jetbrains.kotlinx.benchmark") version "0.4.9"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-benchmark-runtime:0.4.9")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(11)
}
benchmark {
    configurations {

        register("fileWriteText") {
            include("FileWriteBench.*Write")
            warmups = 10
            iterations = 10
            iterationTime = 1
            iterationTimeUnit = "s"
            reportFormat = "text"
        }

        register("fileAppendText") {
            include("FileWriteBench.*Append")
            warmups = 10
            iterations = 10
            iterationTime = 1
            iterationTimeUnit = "s"
            reportFormat = "text"

        }
    }
    targets {
        register("main")
    }
}