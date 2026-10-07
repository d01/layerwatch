plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    // JVM-only (live tests against a real printer). Beyond FtpsClient's port fix, the JDK
    // also fails to resume the FTPS data channel's TLS 1.2 session when it offers session
    // tickets, and treats the printer's no-extended-master-secret sessions as legacy.
    // Android's Conscrypt needs neither.
    systemProperty("jdk.tls.client.enableSessionTicketExtension", "false")
    systemProperty("jdk.tls.allowLegacyResumption", "true")
    System.getProperty("tlsdebug")?.let { systemProperty("javax.net.debug", it) }
}
