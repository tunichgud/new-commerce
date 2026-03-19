// Ersetzt pom.xml — pom.xml bleibt zur Referenz im Repository erhalten.

plugins {
    java
    id("org.springframework.boot") version "3.5.11"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "de.commerce"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

val elasticsearchVersion = "8.12.0"
val langchain4jVersion = "0.36.2"

dependencies {
    // Spring Web
    implementation("org.springframework.boot:spring-boot-starter-web")

    // Elasticsearch Java Client
    implementation("co.elastic.clients:elasticsearch-java:$elasticsearchVersion")

    // Required by elasticsearch-java for JSON mapping (version managed by Spring BOM)
    implementation("com.fasterxml.jackson.core:jackson-databind")

    // Jakarta JSON API required by elasticsearch-java
    implementation("jakarta.json:jakarta.json-api:2.1.3")

    // OpenCSV
    implementation("com.opencsv:opencsv:5.9")

    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // LangChain4j Core
    implementation("dev.langchain4j:langchain4j:$langchain4jVersion")

    // Ollama (für nomic-embed-text Embeddings lokal)
    implementation("dev.langchain4j:langchain4j-ollama:$langchain4jVersion")

    // Google Gemini Flash (für RAG-Agenten)
    implementation("dev.langchain4j:langchain4j-google-ai-gemini:$langchain4jVersion")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:elasticsearch")
    testImplementation("org.testcontainers:junit-jupiter")
    // Needed for IDEs to discover and run JUnit 5 tests directly (outside Gradle task)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
    imports {
        mavenBom("org.testcontainers:testcontainers-bom:2.0.3")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    // Lombok must not be packaged into the fat JAR
    configurations.named("compileOnly") {
        // Lombok is already compileOnly — excluded from runtime classpath automatically
    }
}
