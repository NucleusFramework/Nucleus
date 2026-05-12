val testAnalysisLibraries: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    testAnalysisLibraries("ch.qos.logback:logback-classic:1.4.9")
    testAnalysisLibraries("ch.qos.logback.contrib:logback-jackson:0.1.5")
    testAnalysisLibraries("ch.qos.logback.contrib:logback-json-classic:0.1.5")
    testAnalysisLibraries("commons-logging:commons-logging:1.2")
    testAnalysisLibraries("org.jboss.logging:jboss-logging:3.5.0.Final")
    testAnalysisLibraries("log4j:log4j:1.2.17")
    testAnalysisLibraries("net.java.dev.jna:jna:5.8.0")
    testAnalysisLibraries("org.jline:jline:3.21.0")
    testAnalysisLibraries("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    testAnalysisLibraries("com.google.protobuf:protobuf-java-util:3.21.12")
    testAnalysisLibraries("com.github.ben-manes.caffeine:caffeine:3.1.2")
    testAnalysisLibraries("javax.cache:cache-api:1.1.1")
    testAnalysisLibraries("org.ehcache:ehcache:3.10.8:jakarta")
    testAnalysisLibraries("com.github.luben:zstd-jni:1.5.2-5")
    testAnalysisLibraries("org.apache.commons:commons-compress:1.23.0")
    testAnalysisLibraries("org.apache.httpcomponents:httpclient:4.5.14")
    testAnalysisLibraries("io.netty:netty-common:4.1.115.Final")
    testAnalysisLibraries("io.netty:netty-buffer:4.1.80.Final")
    testAnalysisLibraries("io.netty:netty-transport:4.1.115.Final")
    testAnalysisLibraries("io.netty:netty-handler:4.1.80.Final")
    testAnalysisLibraries("io.netty:netty-codec-http:4.1.80.Final")
    testAnalysisLibraries("io.netty:netty-codec-http2:4.1.80.Final")
    testAnalysisLibraries("io.netty:netty-resolver-dns:4.1.80.Final")
    testAnalysisLibraries("io.undertow:undertow-core:2.3.0.Final")
    testAnalysisLibraries("com.h2database:h2:2.1.210")
    testAnalysisLibraries("com.zaxxer:HikariCP:5.0.1")
    testAnalysisLibraries("org.postgresql:postgresql:42.7.3")
    testAnalysisLibraries("org.mariadb.jdbc:mariadb-java-client:3.0.6")
    testAnalysisLibraries("com.mysql:mysql-connector-j:8.0.31")
    testAnalysisLibraries("org.apache.commons:commons-pool2:2.11.1")
    testAnalysisLibraries("org.apache.commons:commons-dbcp2:2.12.0")
    testAnalysisLibraries("org.apache.tomcat:tomcat-jdbc:10.1.7")
    testAnalysisLibraries("com.graphql-java:graphql-java:19.2")
    testAnalysisLibraries("io.grpc:grpc-netty:1.51.0")
    testAnalysisLibraries("io.grpc:grpc-core:1.69.0")
    testAnalysisLibraries("org.hibernate.orm:hibernate-core:6.6.0.Final")
    testAnalysisLibraries("org.hibernate.validator:hibernate-validator:7.0.4.Final")
    testAnalysisLibraries("org.jooq:jooq:3.18.2")
    testAnalysisLibraries("org.flywaydb:flyway-core:10.20.1")
    testAnalysisLibraries("org.liquibase:liquibase-core:4.17.0")
    testAnalysisLibraries("org.apache.kafka:kafka-clients:3.5.1")
    testAnalysisLibraries("io.nats:jnats:2.16.11")
    testAnalysisLibraries("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    testAnalysisLibraries("io.jsonwebtoken:jjwt-jackson:0.11.5")
    testAnalysisLibraries("org.bouncycastle:bcpkix-jdk18on:1.77")
    testAnalysisLibraries("jakarta.servlet:jakarta.servlet-api:5.0.0")
    testAnalysisLibraries("org.apache.tomcat.embed:tomcat-embed-core:10.0.20")
    testAnalysisLibraries("org.eclipse.jetty:jetty-server:12.0.1")
    testAnalysisLibraries("org.freemarker:freemarker:2.3.31")
    testAnalysisLibraries("org.thymeleaf:thymeleaf:3.1.0.RC1")
    testAnalysisLibraries("org.jetbrains.kotlin:kotlin-stdlib:1.7.10")
    testAnalysisLibraries("org.jetbrains.kotlin:kotlin-reflect:1.7.10")
    testAnalysisLibraries("org.glassfish.jaxb:jaxb-runtime:3.0.2")
    testAnalysisLibraries("com.sun.mail:jakarta.mail:2.0.1")
    testAnalysisLibraries("io.opentelemetry:opentelemetry-sdk-trace:1.19.0")
    testAnalysisLibraries("io.opentelemetry:opentelemetry-exporter-otlp:1.19.0")
    testAnalysisLibraries("org.hdrhistogram:HdrHistogram:2.1.12")
    testAnalysisLibraries("org.mockito:mockito-core:5.0.0")
    testAnalysisLibraries("org.testcontainers:testcontainers:1.19.8")
    testAnalysisLibraries("com.hazelcast:hazelcast:5.2.1")
    testAnalysisLibraries("com.ecwid.consul:consul-api:1.4.5")
    testAnalysisLibraries("org.quartz-scheduler:quartz:2.3.2")
    testAnalysisLibraries("org.eclipse.jgit:org.eclipse.jgit:6.5.0.202303070854-r")
    testAnalysisLibraries("org.jctools:jctools-core:2.1.2")
}

val testZayitLibraries: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    testZayitLibraries("org.apache.lucene:lucene-core:10.3.2")
    testZayitLibraries("org.apache.lucene:lucene-analysis-common:10.3.2")
    testZayitLibraries("org.apache.lucene:lucene-queryparser:10.3.2")
    testZayitLibraries("org.apache.lucene:lucene-highlighter:10.3.2")
    testZayitLibraries("org.xerial:sqlite-jdbc:3.49.1.0")
    testZayitLibraries("app.cash.sqldelight:jdbc-driver:2.3.2")
    testZayitLibraries("app.cash.sqldelight:sqlite-driver:2.3.2")
    testZayitLibraries("io.ktor:ktor-client-core-jvm:3.4.1")
    testZayitLibraries("io.ktor:ktor-client-cio-jvm:3.4.1")
    testZayitLibraries("io.ktor:ktor-client-okhttp-jvm:3.4.1")
    testZayitLibraries("com.squareup.okhttp3:okhttp-jvm:5.3.2")
    testZayitLibraries("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.10.0")
    testZayitLibraries("org.jetbrains.kotlinx:kotlinx-serialization-protobuf-jvm:1.10.0")
    testZayitLibraries("org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.10.0")
    testZayitLibraries("com.github.luben:zstd-jni:1.5.7-7")
    testZayitLibraries("com.github.oshi:oshi-core:6.10.0")
    testZayitLibraries("org.slf4j:slf4j-api:2.0.17")
    testZayitLibraries("org.slf4j:slf4j-simple:2.0.17")
    testZayitLibraries("io.sentry:sentry:8.36.0")
    testZayitLibraries("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2")
    testZayitLibraries("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
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
    val zayitMetadataDir =
        providers
            .gradleProperty("test.zayit.metadata.dir")
            .orElse(providers.environmentVariable("ZAYIT_METADATA_DIR"))
            .orElse("")
    systemProperty("test.zayit.metadata.dir", zayitMetadataDir.get())
}
