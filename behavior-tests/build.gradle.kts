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
// 실행 opt-in: env BEHAVIOR_TESTS=1 (run.sh가 export한다). DB_URL은 앱 datasource 키와 같아서 판정에 쓰지 않는다
// (앱용으로 export된 DB_URL 때문에 루트 build가 개발 DB에 픽스처 행을 쓰는 것을 막는다).
//  - opt-in 없음 + 루트 집계(build·test·check)로 끌려옴 → 이유를 남기고 건너뜀
//  - opt-in 없음 + 행위 테스트 태스크를 직접(축약 포함) 호출 → 실패(0건 성공이 red를 green으로 보이게 하므로)
//  - opt-in 있음 + infraTest → DB_URL, contractTest·contract2Test·contract3Test → BASE_URL·DB_URL 명시돼야 함, 없으면 실패
val behaviorOptIn: Boolean = System.getenv("BEHAVIOR_TESTS") == "1"
val behaviorTaskNames = setOf("test", "infraTest", "contractTest", "contract2Test", "contract3Test")

/** 이름을 Gradle 축약 단위로 쪼갠다: '-' 경계와 대문자 앞(camelCase). 예: contractTest → [contract, Test]. */
fun abbrevParts(name: String): List<String> = name.split('-').flatMap { it.split(Regex("(?=[A-Z0-9])")) }.filter { it.isNotEmpty() }

/** Gradle 축약 규칙: 패턴 조각마다 대상 조각의 접두(대소문자 무시). 예: cT·contractT·iT·tes. */
fun abbrevMatches(pattern: String, name: String): Boolean {
    val p = abbrevParts(pattern)
    val n = abbrevParts(name)
    return p.isNotEmpty() && p.size <= n.size && p.indices.all { n[it].lowercase().startsWith(p[it].lowercase()) }
}

/**
 * 이 모듈의 행위 테스트 태스크를 사용자가 직접(축약 포함) 불렀나. 요청 문자열을 Gradle 축약 규칙으로 해석한다.
 *  - 경로 형태(`:behavior-tests:cT`, `b-t:iT`): 프로젝트 조각이 이 모듈과 맞고 태스크 조각이 behaviorTaskNames 중 하나와 맞으면 직접
 *  - 이름만: infraTest·contractTest·contract2Test·contract3Test와 맞으면 어디서 불러도 직접(이 모듈에만 있는 이름), test와만 맞으면 모듈 디렉터리에서 부른 경우만 직접
 *    (루트의 test·tes·build·check는 집계 → 건너뜀)
 * 판정 뒤 실제 그래프에 그 태스크가 있는 것만 남긴다(축약이 다른 태스크로 해석된 경우를 걸러 낸다).
 */
fun directlyRequested(graph: TaskExecutionGraph): List<String> {
    val inModuleDir = gradle.startParameter.currentDir.canonicalFile == projectDir.canonicalFile
    val projectName = project.path.removePrefix(":")
    val hits = mutableListOf<String>()
    for (raw in gradle.startParameter.taskNames) {
        val absolute = raw.startsWith(":")
        val segs = raw.removePrefix(":").split(":")
        val taskPart = segs.last()
        val projPart = segs.dropLast(1)
        val matched: List<String> = when {
            projPart.isNotEmpty() ->
                if (projPart.size == 1 && abbrevMatches(projPart[0], projectName)) behaviorTaskNames.filter { abbrevMatches(taskPart, it) } else emptyList()
            absolute -> emptyList()                                   // `:x` = 루트 프로젝트의 태스크
            else -> behaviorTaskNames.filter { abbrevMatches(taskPart, it) && (it != "test" || inModuleDir) }
        }
        matched.map { "${project.path}:$it" }.filter { graph.hasTask(it) }.forEach { hits += "$raw → $it" }
    }
    return hits
}

fun Test.requireOptIn() {
    onlyIf("env BEHAVIOR_TESTS=1") {
        if (!behaviorOptIn) logger.lifecycle("[behavior-tests] $path 건너뜀: env BEHAVIOR_TESTS=1 이 없다(행위 테스트는 run.sh 또는 명시적 opt-in으로만 돈다)")
        behaviorOptIn
    }
}

gradle.taskGraph.whenReady {
    val direct = directlyRequested(this)
    if (!behaviorOptIn && direct.isNotEmpty()) {
        throw GradleException(
            "행위 테스트 ${direct}를 직접 호출했지만 env BEHAVIOR_TESTS=1 이 없다. " +
                "건너뛰면 0건 성공으로 보이므로 실패시킨다. tools/behavior/run.sh 를 쓰거나 " +
                "BEHAVIOR_TESTS=1 BASE_URL=... DB_URL=... 로 호출하라."
        )
    }
    if (behaviorOptIn && hasTask("${project.path}:infraTest") && System.getenv("DB_URL").isNullOrBlank()) {
        throw GradleException("infraTest에는 env DB_URL 이 명시돼야 한다(코드 기본 주소로 붙어 픽스처 행을 쓰지 않게).")
    }
    for (name in listOf("contractTest", "contract2Test", "contract3Test")) {
        if (behaviorOptIn && hasTask("${project.path}:$name")) {
            val missing = listOf("BASE_URL", "DB_URL").filter { System.getenv(it).isNullOrBlank() }
            if (missing.isNotEmpty()) {
                throw GradleException("${name}에는 env $missing 가 명시돼야 한다(코드 기본값으로 엉뚱한 앱·DB를 보지 않게).")
            }
        }
    }
}

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
    requireOptIn()
}

val contractTest by tasks.registering(Test::class) {
    description = "ADR-0003 1단계 계약 테스트 (태그 contract). BASE_URL의 앱을 대상으로 한다"
    group = "verification"
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    useJUnitPlatform { includeTags("contract") }
    // 2단계·단계 A 패키지는 태그를 잘못 달아도 1단계 대상에 섞이지 않게 뺀다
    filter {
        excludeTestsMatching("com.ccle.behavior.contract2.*")
        excludeTestsMatching("com.ccle.behavior.contract3.*")
    }
    behaviorDefaults()
    requireOptIn()
    mustRunAfter(infraTest)
}

// 2단계 계약 테스트(ADR-0004, 패키지 com.ccle.behavior.contract2, 태그 contract2). 1단계 test(infraTest → contractTest)에는 넣지 않는다.
// 실행: tools/behavior/run.sh <god|layered> contract2Test
val contract2Test by tasks.registering(Test::class) {
    description = "ADR-0004 2단계 계약 테스트 (패키지 contract2, 태그 contract2). BASE_URL의 앱을 대상으로 한다"
    group = "verification"
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    useJUnitPlatform { includeTags("contract2") }
    filter { includeTestsMatching("com.ccle.behavior.contract2.*") }
    behaviorDefaults()
    requireOptIn()
    mustRunAfter(infraTest, contractTest)
}

// 단계 A 계약 테스트(ADR-0007, 패키지 com.ccle.behavior.contract3, 태그 contract3). test·contract2Test에는 넣지 않는다.
// 실행: tools/behavior/run.sh <god|layered> contract3Test
val contract3Test by tasks.registering(Test::class) {
    description = "ADR-0007 단계 A 계약 테스트 (패키지 contract3, 태그 contract3). BASE_URL의 앱을 대상으로 한다"
    group = "verification"
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    useJUnitPlatform { includeTags("contract3") }
    filter { includeTestsMatching("com.ccle.behavior.contract3.*") }
    behaviorDefaults()
    requireOptIn()
    mustRunAfter(infraTest, contractTest, contract2Test)
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
