import java.security.MessageDigest

plugins {
    java
    id("io.quarkus")
    id("com.diffplug.spotless") version "8.10.2"
    checkstyle
    pmd
    jacoco
}

repositories {
    mavenLocal() // Optional: for testing locally
    mavenCentral() // Public artifacts
    maven {
        name = "GitHubPackages"
        url = uri("https://maven.pkg.github.com/AI-Solutions-App/ai-solutions-java-shared")
        credentials {
            username = System.getenv("GITHUB_ACTOR")
            password = System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation(
        enforcedPlatform(
            "${property("quarkusPlatformGroupId")}:${property("quarkusPlatformArtifactId")}:${property("quarkusPlatformVersion")}",
        ),
    )

    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-rest-jackson")
    // Generates an OpenAPI spec from the JAX-RS annotations below, served at /q/openapi
    // and /q/swagger-ui. The docs site's API reference section is generated from this.
    implementation("io.quarkus:quarkus-smallrye-openapi")
    implementation("io.quarkus:quarkus-rest-client-jackson")
    implementation("io.quarkus:quarkus-hibernate-reactive-panache")
    implementation("io.quarkus:quarkus-reactive-mysql-client")
    implementation("io.quarkus:quarkus-vertx")
    // JWT verification against org-api's published JWKS (handles key fetch, caching and
    // rotation; GraalVM native safe) — required by IdentityClaimsExtractor
    implementation("io.quarkus:quarkus-smallrye-jwt")
    // CompanyDbClient's companyId -> dbName lookup cache
    implementation("io.quarkus:quarkus-cache")
    // Kafka messaging + jackson serializer
    implementation("io.quarkus:quarkus-messaging-kafka")
    // Drives the shared notification outbox relay's @Scheduled poll
    implementation("io.quarkus:quarkus-scheduler")
    implementation("io.quarkus:quarkus-jackson")
    // Lombok
    compileOnly("org.projectlombok:lombok:1.18.42")
    annotationProcessor("org.projectlombok:lombok:1.18.42")

    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.quarkus:quarkus-junit5-mockito")
    testImplementation("io.quarkus:quarkus-test-security-jwt")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testImplementation("org.testcontainers:mysql")
    testImplementation("org.testcontainers:kafka")
    testImplementation("org.testcontainers:junit-jupiter")
    // Testcontainers' own JDBC readiness check needs a blocking JDBC driver on the
    // classpath — the app itself only ever uses the reactive Vert.x MySQL client.
    testRuntimeOnly("com.mysql:mysql-connector-j:9.4.0")

    // MavenLocal
    implementation("com.aisolutions:ai-solutions-java-shared:0.6.3")

    // Google API Client Libraries
    implementation("com.google.api-client:google-api-client:2.8.0")
    implementation("com.google.oauth-client:google-oauth-client-jetty:1.39.0")
    implementation("com.google.apis:google-api-services-gmail:v1-rev20250616-2.0.0")
    implementation("com.google.auth:google-auth-library-oauth2-http:1.37.1")

    // ───── JSON Processing ─────
    implementation("com.google.code.gson:gson:2.13.1")
    implementation("com.sun.mail:jakarta.mail:2.0.1")
    implementation("jakarta.activation:jakarta.activation-api:2.1.3")

    // Apache Commons Net - FTP client library
    implementation("commons-net:commons-net:3.10.0")
}

group = "com.aisolutions"
version = "0.0.1"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<Test> {
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
}

tasks.withType<Test> {
    failOnNoDiscoveredTests = false
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:deprecation")
    options.isDeprecation = true
}

// Only unchanged legacy Java files remain exempt while conventions are adopted.
val legacyJavaBaseline = file("config/conventions/legacy-java-baseline.tsv")
    .readLines()
    .filter { it.isNotBlank() && !it.startsWith("#") }
    .groupBy { line -> line.substringAfter('\t') }
    .mapValues { (_, entries) -> entries.map { line -> line.substringBefore('\t') }.toSet() }
val javaFilesRequiringConventions = files(provider {
    fileTree("src") { include("**/*.java") }.files.filter { sourceFile ->
        val sourcePath = sourceFile.relativeTo(projectDir).invariantSeparatorsPath
        val currentDigest = MessageDigest.getInstance("SHA-256")
            .digest(sourceFile.readBytes()).joinToString("") { "%02x".format(it) }
        currentDigest !in legacyJavaBaseline[sourcePath].orEmpty()
    }
})

spotless {
    java {
        target(javaFilesRequiringConventions)
        palantirJavaFormat("2.97.0")
        removeUnusedImports()
        // "\\#" catches static imports; placing it last matches checkstyle.xml's
        // ImportOrder option="bottom" — otherwise spotlessApply and checkstyleCheck
        // fight over static-import placement on every file that has any.
        importOrder("java", "javax", "jakarta", "", "org.acme", "\\#")
    }
}

checkstyle {
    toolVersion = "14.1.0"
    configFile = file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
}

pmd {
    toolVersion = "7.27.0"
    ruleSetFiles = files("config/pmd/ruleset.xml")
    ruleSets = emptyList()
    isIgnoreFailures = false
}

jacoco {
    toolVersion = "0.8.15"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}

tasks.withType<Checkstyle>().configureEach {
    if (name == "checkstyleMain" || name == "checkstyleTest") {
        setSource(javaFilesRequiringConventions.filter { it.path.contains("/src/${if (name == "checkstyleTest") "test" else "main"}/") })
    }
}

tasks.withType<Pmd>().configureEach {
    if (name == "pmdMain" || name == "pmdTest") {
        setSource(javaFilesRequiringConventions.filter { it.path.contains("/src/${if (name == "pmdTest") "test" else "main"}/") })
    }
}

tasks.register<Copy>("installGitHooks") {
    from("scripts/pre-commit", "scripts/commit-msg")
    into(".git/hooks")
    doLast {
        file(".git/hooks/pre-commit").setExecutable(true)
        file(".git/hooks/commit-msg").setExecutable(true)
    }
}
