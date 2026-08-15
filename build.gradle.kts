import java.util.zip.ZipFile

plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "com.playcity"
version = "0.3.2"
val pluginVersion = version.toString()

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.112-stable")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    implementation("org.postgresql:postgresql:42.7.5")
    compileOnly("org.mariadb.jdbc:mariadb-java-client:3.5.2")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.papermc.paper:paper-api:26.2.build.112-stable")
    testImplementation("org.mariadb.jdbc:mariadb-java-client:3.5.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
}

tasks.processResources {
    inputs.property("pluginVersion", pluginVersion)
    filesMatching("plugin.yml") {
        expand("version" to pluginVersion)
    }
    from("LICENSE") {
        into("META-INF")
        rename { "LICENSE-Stats.txt" }
    }
    from("THIRD_PARTY_NOTICES.md") {
        into("META-INF")
    }
    from("licenses") {
        exclude("LGPL-2.1-or-later-MariaDB-Connector-J.txt")
        into("META-INF/licenses/Stats")
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    mergeServiceFiles()
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.jar {
    enabled = false
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

val verifyDistribution = tasks.register("verifyDistribution") {
    dependsOn(tasks.shadowJar)
    doLast {
        val archive = tasks.shadowJar.get().archiveFile.get().asFile
        ZipFile(archive).use { zip ->
            check(zip.getEntry("com/playcity/stats/StatsPlugin.class") != null)
            check(zip.getEntry("org/mariadb/jdbc/Driver.class") == null) {
                "MariaDB Connector/J must remain an external Paper library"
            }
            check(zip.entries().asSequence().none { entry ->
                entry.name.startsWith("org/bukkit/") || entry.name.startsWith("io/papermc/")
            }) { "Paper/Bukkit API must not be bundled" }
            listOf(
                "META-INF/LICENSE-Stats.txt",
                "META-INF/THIRD_PARTY_NOTICES.md",
                "META-INF/licenses/Stats/Apache-2.0.txt",
                "META-INF/licenses/Stats/BSD-2-Clause-OnGres-SCRAM-2017.txt",
                "META-INF/licenses/Stats/BSD-2-Clause-OnGres-StringPrep-2019.txt",
                "META-INF/licenses/Stats/BSD-2-Clause-pgJDBC.txt",
                "META-INF/licenses/Stats/BSD-2-Clause-Zentus.txt",
                "META-INF/licenses/Stats/MIT-Checker-Qual.txt",
                "META-INF/licenses/Stats/MIT-SLF4J.txt",
                "META-INF/licenses/Stats/NOTICE-sqlite-jdbc.txt"
            ).forEach { entry ->
                check(zip.getEntry(entry) != null) { "Missing distribution notice: $entry" }
            }
        }
    }
}

tasks.check {
    dependsOn(verifyDistribution)
}
