import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    kotlin("jvm")
    `java-gradle-plugin`
    alias(libs.plugins.pluginPublish)
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(gradleApi())

    compileOnly(localGroovy())
    compileOnly(kotlin("gradle-plugin"))
    compileOnly(kotlin("native-utils"))
    compileOnly(libs.agp)
    compileOnly(libs.agp.api)

    implementation(libs.download.task)
    implementation(libs.kotlin.poet)
    implementation(libs.batik.transcoder)
    implementation(libs.thumbnailator)
    implementation(libs.asm)

    testImplementation(libs.junit)
}

// Test configurations for analyzer integration tests
val testAnalysisLibraries: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive = false // Only the JARs themselves, not their transitive deps
}

// All libraries from the Oracle GraalVM Reachability Metadata Repository (v0.10.6)
// that can be resolved from Maven Central. Each matches a tested-version from the repo.
dependencies {
    // Logging
    testAnalysisLibraries("ch.qos.logback:logback-classic:1.4.9")
    testAnalysisLibraries("ch.qos.logback.contrib:logback-jackson:0.1.5")
    testAnalysisLibraries("ch.qos.logback.contrib:logback-json-classic:0.1.5")
    testAnalysisLibraries("commons-logging:commons-logging:1.2")
    testAnalysisLibraries("org.jboss.logging:jboss-logging:3.5.0.Final")
    testAnalysisLibraries("log4j:log4j:1.2.17")

    // JNA
    testAnalysisLibraries("net.java.dev.jna:jna:5.8.0")

    // CLI / terminal
    testAnalysisLibraries("org.jline:jline:3.21.0")

    // Serialization / JSON
    testAnalysisLibraries("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    testAnalysisLibraries("com.google.protobuf:protobuf-java-util:3.21.12")

    // Caching
    testAnalysisLibraries("com.github.ben-manes.caffeine:caffeine:3.1.2")
    testAnalysisLibraries("javax.cache:cache-api:1.1.1")
    testAnalysisLibraries("org.ehcache:ehcache:3.10.8:jakarta")

    // Compression
    testAnalysisLibraries("com.github.luben:zstd-jni:1.5.2-5")
    testAnalysisLibraries("org.apache.commons:commons-compress:1.23.0")

    // Networking / HTTP
    testAnalysisLibraries("org.apache.httpcomponents:httpclient:4.5.14")
    testAnalysisLibraries("io.netty:netty-common:4.1.115.Final")
    testAnalysisLibraries("io.netty:netty-buffer:4.1.80.Final")
    testAnalysisLibraries("io.netty:netty-transport:4.1.115.Final")
    testAnalysisLibraries("io.netty:netty-handler:4.1.80.Final")
    testAnalysisLibraries("io.netty:netty-codec-http:4.1.80.Final")
    testAnalysisLibraries("io.netty:netty-codec-http2:4.1.80.Final")
    testAnalysisLibraries("io.netty:netty-resolver-dns:4.1.80.Final")
    testAnalysisLibraries("io.undertow:undertow-core:2.3.0.Final")

    // JDBC / databases
    testAnalysisLibraries("com.h2database:h2:2.1.210")
    testAnalysisLibraries("com.zaxxer:HikariCP:5.0.1")
    testAnalysisLibraries("org.postgresql:postgresql:42.7.3")
    testAnalysisLibraries("org.mariadb.jdbc:mariadb-java-client:3.0.6")
    testAnalysisLibraries("com.mysql:mysql-connector-j:8.0.31")
    testAnalysisLibraries("org.apache.commons:commons-pool2:2.11.1")
    testAnalysisLibraries("org.apache.commons:commons-dbcp2:2.12.0")
    testAnalysisLibraries("org.apache.tomcat:tomcat-jdbc:10.1.7")

    // GraphQL
    testAnalysisLibraries("com.graphql-java:graphql-java:19.2")

    // gRPC
    testAnalysisLibraries("io.grpc:grpc-netty:1.51.0")
    testAnalysisLibraries("io.grpc:grpc-core:1.69.0")

    // ORM / persistence
    testAnalysisLibraries("org.hibernate.orm:hibernate-core:6.6.0.Final")
    testAnalysisLibraries("org.hibernate.validator:hibernate-validator:7.0.4.Final")
    testAnalysisLibraries("org.jooq:jooq:3.18.2")
    testAnalysisLibraries("org.flywaydb:flyway-core:10.20.1")
    testAnalysisLibraries("org.liquibase:liquibase-core:4.17.0")

    // Messaging
    testAnalysisLibraries("org.apache.kafka:kafka-clients:3.5.1")
    testAnalysisLibraries("io.nats:jnats:2.16.11")
    testAnalysisLibraries("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")

    // Security / crypto
    testAnalysisLibraries("io.jsonwebtoken:jjwt-jackson:0.11.5")
    testAnalysisLibraries("org.bouncycastle:bcpkix-jdk18on:1.77")

    // Web / servlet
    testAnalysisLibraries("jakarta.servlet:jakarta.servlet-api:5.0.0")
    testAnalysisLibraries("org.apache.tomcat.embed:tomcat-embed-core:10.0.20")
    testAnalysisLibraries("org.eclipse.jetty:jetty-server:12.0.1")

    // Template engines
    testAnalysisLibraries("org.freemarker:freemarker:2.3.31")
    testAnalysisLibraries("org.thymeleaf:thymeleaf:3.1.0.RC1")

    // Kotlin
    testAnalysisLibraries("org.jetbrains.kotlin:kotlin-stdlib:1.7.10")
    testAnalysisLibraries("org.jetbrains.kotlin:kotlin-reflect:1.7.10")

    // XML / JAXB
    testAnalysisLibraries("org.glassfish.jaxb:jaxb-runtime:3.0.2")

    // Mail
    testAnalysisLibraries("com.sun.mail:jakarta.mail:2.0.1")

    // Observability
    testAnalysisLibraries("io.opentelemetry:opentelemetry-sdk-trace:1.19.0")
    testAnalysisLibraries("io.opentelemetry:opentelemetry-exporter-otlp:1.19.0")
    testAnalysisLibraries("org.hdrhistogram:HdrHistogram:2.1.12")

    // Testing
    testAnalysisLibraries("org.mockito:mockito-core:5.0.0")
    testAnalysisLibraries("org.testcontainers:testcontainers:1.19.8")

    // Misc
    testAnalysisLibraries("com.hazelcast:hazelcast:5.2.1")
    testAnalysisLibraries("com.ecwid.consul:consul-api:1.4.5")
    testAnalysisLibraries("org.quartz-scheduler:quartz:2.3.2")
    testAnalysisLibraries("org.eclipse.jgit:org.eclipse.jgit:6.5.0.202303070854-r")
    testAnalysisLibraries("org.jctools:jctools-core:2.1.2")
}

// Zayit (SeforimApp) runtime dependencies for real-world analyzer validation
val testZayitLibraries: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    // Lucene (core search engine - heavy reflection)
    testZayitLibraries("org.apache.lucene:lucene-core:10.3.2")
    testZayitLibraries("org.apache.lucene:lucene-analysis-common:10.3.2")
    testZayitLibraries("org.apache.lucene:lucene-queryparser:10.3.2")
    testZayitLibraries("org.apache.lucene:lucene-highlighter:10.3.2")

    // SQLite / JDBC
    testZayitLibraries("org.xerial:sqlite-jdbc:3.49.1.0")
    testZayitLibraries("app.cash.sqldelight:jdbc-driver:2.3.2")
    testZayitLibraries("app.cash.sqldelight:sqlite-driver:2.3.2")

    // Networking
    testZayitLibraries("io.ktor:ktor-client-core-jvm:3.4.1")
    testZayitLibraries("io.ktor:ktor-client-cio-jvm:3.4.1")
    testZayitLibraries("io.ktor:ktor-client-okhttp-jvm:3.4.1")
    testZayitLibraries("com.squareup.okhttp3:okhttp-jvm:5.3.2")

    // Serialization
    testZayitLibraries("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.10.0")
    testZayitLibraries("org.jetbrains.kotlinx:kotlinx-serialization-protobuf-jvm:1.10.0")
    testZayitLibraries("org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.10.0")

    // Compression / native
    testZayitLibraries("com.github.luben:zstd-jni:1.5.7-7")
    testZayitLibraries("com.github.oshi:oshi-core:6.10.0")

    // Logging
    testZayitLibraries("org.slf4j:slf4j-api:2.0.17")
    testZayitLibraries("org.slf4j:slf4j-simple:2.0.17")

    // Crash reporting
    testZayitLibraries("io.sentry:sentry:8.36.0")

    // Coroutines
    testZayitLibraries("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2")
    testZayitLibraries("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")

    // Kotlin
    testZayitLibraries("org.jetbrains.kotlin:kotlin-stdlib:2.3.20")
}

val testOracleRepo: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    testOracleRepo("org.graalvm.buildtools:graalvm-reachability-metadata:0.10.6:repository@zip")
}

tasks.withType<Test> {
    maxHeapSize = "1g"
    systemProperty("test.analysis.libraries", testAnalysisLibraries.asPath)
    systemProperty("test.oracle.repo.zip", testOracleRepo.singleFile.absolutePath)
    systemProperty("test.zayit.libraries", testZayitLibraries.asPath)
    // Zayit metadata dir: set via local property or env var, falls back to empty (tests use assumeTrue)
    val zayitMetadataDir =
        providers
            .gradleProperty("test.zayit.metadata.dir")
            .orElse(providers.environmentVariable("ZAYIT_METADATA_DIR"))
            .orElse("")
    systemProperty("test.zayit.metadata.dir", zayitMetadataDir.get())
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        freeCompilerArgs.add("-opt-in=dev.nucleusframework.nucleus.ExperimentalNucleusLibrary")
    }
}

// BuildConfig generation
val buildConfigDir
    get() = project.layout.buildDirectory.dir("generated/buildconfig")
val composeVersion = project.findProperty("compose.version")?.toString() ?: "1.10.0"
val composeMaterial3Version = project.findProperty("compose.material3.version")?.toString() ?: "1.9.0"
val pluginVersion = project.version.toString()
val buildConfig =
    tasks.register("buildConfig", GenerateBuildConfig::class.java) {
        classFqName.set("dev.nucleusframework.nucleus.NucleusBuildConfig")
        generatedOutputDir.set(buildConfigDir)
        fieldsToGenerate.put("composeVersion", composeVersion)
        fieldsToGenerate.put("composeMaterial3Version", composeMaterial3Version)
        fieldsToGenerate.put("composeGradlePluginVersion", composeVersion)
    }
tasks.named("compileKotlin", KotlinCompilationTask::class) {
    dependsOn(buildConfig)
}
sourceSets.main.configure {
    java.srcDir(buildConfig.flatMap { it.generatedOutputDir })
}

gradlePlugin {
    plugins {
        create(property("ID").toString()) {
            id = property("ID").toString()
            implementationClass = property("IMPLEMENTATION_CLASS").toString()
            version = project.version.toString()
            description = property("DESCRIPTION").toString()
            displayName = property("DISPLAY_NAME").toString()
            tags.set(listOf("nucleus", "desktop", "jvm", "packaging"))
        }
    }
}

gradlePlugin {
    website.set(property("WEBSITE").toString())
    vcsUrl.set(property("VCS_URL").toString())
}

// Use Detekt with type resolution for check
tasks.named("check").configure {
    this.setDependsOn(
        this.dependsOn.filterNot {
            it is TaskProvider<*> && it.name == "detekt"
        } + tasks.named("detektMain"),
    )
}

tasks.register("setupPluginUploadFromEnvironment") {
    doLast {
        val key = System.getenv("GRADLE_PUBLISH_KEY")
        val secret = System.getenv("GRADLE_PUBLISH_SECRET")

        if (key == null || secret == null) {
            throw GradleException("gradlePublishKey and/or gradlePublishSecret are not defined environment variables")
        }

        System.setProperty("gradle.publish.key", key)
        System.setProperty("gradle.publish.secret", secret)
    }
}
