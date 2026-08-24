plugins {
    java
    id("io.quarkus")
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
    implementation("io.quarkus:quarkus-jackson")
    // Lombok
    compileOnly("org.projectlombok:lombok:1.18.42")
    annotationProcessor("org.projectlombok:lombok:1.18.42")

    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.rest-assured:rest-assured")

    // MavenLocal
    implementation("com.aisolutions:ai-solutions-java-shared:0.1.1")

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
