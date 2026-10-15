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

val testSourceSet = sourceSets["test"]

val infraTest by tasks.registering(Test::class) {
    description = "가짜 PG·DB 도우미 자체 테스트 + verify.sql(시드·도우미 행) (태그 infra)"
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
    mustRunAfter(infraTest)
}

// 기본 test = infraTest 다음 contractTest. test 자신은 테스트를 돌리지 않는다
// (섞어 돌리면 VerifySqlTest의 판정 대상이 실행 순서에 따라 바뀌므로).
tasks.test {
    dependsOn(infraTest, contractTest)
    enabled = false
}

// 가짜 PG만 따로 띄운다: ./gradlew :behavior-tests:fakePg  (env PG_PORT, PG_DELAY_MS). 준비되면 "fake-pg ready <port>"
val fakePg by tasks.registering(JavaExec::class) {
    description = "가짜 PG 서버를 단독 실행 (ADR-0003 §6)"
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.ccle.behavior.pg.FakePgKt")
}

// run.sh용: gradle 없이 java로 가짜 PG를 띄울 클래스패스를 파일로 쓴다(build/fake-pg.classpath).
//   java -cp "$(cat behavior-tests/build/fake-pg.classpath)" com.ccle.behavior.pg.FakePgKt
val fakePgClasspath by tasks.registering {
    description = "가짜 PG 실행 클래스패스를 build/fake-pg.classpath에 쓴다"
    group = "application"
    val cp = sourceSets["main"].runtimeClasspath
    val out = layout.buildDirectory.file("fake-pg.classpath")
    dependsOn(tasks.named("classes"))
    inputs.files(cp)
    outputs.file(out)
    doLast { out.get().asFile.writeText(cp.files.joinToString(File.pathSeparator) { it.absolutePath }) }
}
