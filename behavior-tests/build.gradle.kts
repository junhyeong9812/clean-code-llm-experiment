// HTTP 행위 테스트 (ADR-0003 1단계). 앱 내부를 모르는 블랙박스 테스트: HTTP + DB 테이블만 접점.
// 같은 테스트를 BASE_URL만 바꿔 god·layered에 돌린다. 루트의 Spring 설정은 받지 않는다.
plugins {
    kotlin("jvm")
}

kotlin { jvmToolchain(21) }

repositories { mavenCentral() }

dependencies {
    // 가짜 PG(main)도 JSON을 쓴다
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.19.2")

    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.postgresql:postgresql:42.7.7")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 테스트는 직렬로 돈다(ADR-0003 §11, 명세 §2): 포크 1개, JUnit 병렬 끔.
// 실행 결과는 앱·DB 상태에 달려 있으므로 캐시하지 않는다.
fun Test.behaviorDefaults() {
    maxParallelForks = 1
    systemProperty("junit.jupiter.execution.parallel.enabled", "false")
    outputs.upToDateWhen { false }
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.SHORT
    }
}

tasks.test {
    useJUnitPlatform()  // infra + contract 둘 다
    behaviorDefaults()
}

val testSourceSet = sourceSets["test"]

val infraTest by tasks.registering(Test::class) {
    description = "가짜 PG·DB 도우미 자체 테스트 (태그 infra)"
    group = "verification"
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    useJUnitPlatform { includeTags("infra") }
    behaviorDefaults()
}

val contractTest by tasks.registering(Test::class) {
    description = "ADR-0003 1단계 계약 테스트 (태그 contract). BASE_URL의 앱을 대상으로 한다"
    group = "verification"
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    useJUnitPlatform { includeTags("contract") }
    behaviorDefaults()
}

// 가짜 PG만 따로 띄운다: ./gradlew :behavior-tests:fakePg  (env PG_PORT, PG_DELAY_MS)
val fakePg by tasks.registering(JavaExec::class) {
    description = "가짜 PG 서버를 단독 실행 (ADR-0003 §6)"
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.ccle.behavior.pg.FakePgKt")
}
