plugins {
    kotlin("jvm") version "1.9.22"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.json:json:20240303")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("GlavniKt")
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8")
}

kotlin {
    jvmToolchain(17)
}

tasks.withType<JavaExec>().configureEach {
    jvmArgs("-Dfile.encoding=UTF-8")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Dfile.encoding=UTF-8")
}

// "Fat JAR" - jedan .jar fajl koji sadrzi sve zavisnosti unutra.
// Pokretanje: java -jar build/libs/baze-kt-fat.jar --sema primeri/k2_jun.json --upit "..."
// Ne zahteva ni IntelliJ ni Gradle ni internet na ciljnom racunaru - samo Javu 17+.
tasks.register<Jar>("fatJar") {
    archiveClassifier.set("fat")
    manifest { attributes["Main-Class"] = "GlavniKt" }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
    })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
