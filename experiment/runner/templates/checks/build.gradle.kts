// 과제 확인 테스트(HTTP·DB 수준). 실행 중인 앱(BASE_URL)과 DB(DB_URL)만 본다 — 앱 코드에 의존하지 않는다.
plugins {
    kotlin("jvm")
}

kotlin { jvmToolchain(21) }

repositories { mavenCentral() }

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.postgresql:postgresql:42.7.7")
    testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.19.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    maxParallelForks = 1
    outputs.upToDateWhen { false }
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
