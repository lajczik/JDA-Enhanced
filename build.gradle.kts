/*
 * Copyright 2015 Austin Keener, Michael Ritter, Florian Spieß, and the JDA contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.diffplug.spotless.LineEnding
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import net.dv8tion.jda.gradle.Version
import net.dv8tion.jda.gradle.plugins.applyAudioExclusions
import net.dv8tion.jda.gradle.plugins.applyNettyExclusions
import net.dv8tion.jda.gradle.plugins.applyOpusExclusions
import net.dv8tion.jda.gradle.tasks.VerifyBytecodeVersion
import net.ltgt.gradle.errorprone.errorprone
import nl.littlerobots.vcu.plugin.resolver.VersionSelectors
import org.jetbrains.gradle.ext.Gradle as GradleRunConfiguration
import org.jetbrains.gradle.ext.JUnit as JUnitRunConfiguration
import org.jetbrains.gradle.ext.copyright
import org.jetbrains.gradle.ext.runConfigurations
import org.jetbrains.gradle.ext.settings

plugins {
    artifacts
    environment
    idea
    `model-generator`
    `java-library`
    `maven-publish`
    signing

    // Provided via buildSrc on the buildscript classpath; version cannot be specified here
    id("com.gradleup.shadow")
    alias(libs.plugins.versions)
    alias(libs.plugins.version.catalog.update)
    alias(libs.plugins.spotless)
    alias(libs.plugins.errorprone)
    alias(libs.plugins.ideax)
    alias(libs.plugins.nmcp)
    alias(libs.plugins.nmcp.aggregation)
}


////////////////////////////////////
//                                //
//     Project Configuration      //
//                                //
////////////////////////////////////

val exampleJavaVersion = JavaLanguageVersion.of(25)
val libraryJavaVersion = JavaLanguageVersion.of(21)
group = "io.github.lajczik"
version = "6.7.0"

projectEnvironment {
    version = Version(major = "6", minor = "7", revision = "0", classifier = null)
}

artifactFilters {
    opusExclusions.addAll(
        "club.minnced:opus-java",
        "club.minnced:opus-java-api",
        "club.minnced:opus-java-natives",
        "net.java.dev.jna:jna",
        "tomp2p:opus-wrapper",
    )
    additionalAudioExclusions.addAll(
        "com.google.crypto.tink:tink",
        "com.google.protobuf:protobuf-java",
        "com.google.code.gson:gson",
    )
    nettyExclusions.addAll(
        "io.netty:netty-transport-classes-epoll",
        "io.netty:netty-transport-native-epoll",
        "com.github.luben:zstd-jni",
        // Reactor Netty transitive modules unused by JDA
        "io.netty:netty-handler-proxy",
        "io.netty:netty-codec-socks",
        "io.netty:netty-codec-http2",
    )
}


apiModelGenerator {
    outputDirectory = layout.buildDirectory.dir("generated/rest-api-models")
    apiSpecFile = file("discord-rest-openapi.json")
    apiSpecDownloadUrl = "https://raw.githubusercontent.com/discord/discord-api-spec/refs/heads/main/specs/openapi.json"

    generatorSuffix = "Dto"
    includes = listOf(
            "AvailableLocalesEnum",
            "CreateRoleRequest",
            "MessageType",
            "ChannelTypes",
            "AuditLogActionTypes",
            "InviteTypes",
            "WebhookTypes",
    )
}

idea {
    project {
        settings {
            copyright {
                val jdaCopyrightProfileName = "JDA"

                useDefault = jdaCopyrightProfileName

                profiles.create(jdaCopyrightProfileName) {
                    notice = file("gradle/copyright-header.txt").readText(Charsets.UTF_8)
                }
            }

            runConfigurations {
                defaults(JUnitRunConfiguration::class.java) {
                    vmParameters = listOf(
                            "-ea",
                            "-Duser.timezone=GMT",
                            "-Duser.language=en",
                            "-Duser.country=US",
                            "-Dfile.encoding=utf-8",
                            "-DupdateSnapshots",
                    ).joinToString(" ")
                }

                register<GradleRunConfiguration>("format") {
                    taskNames = listOf("format")
                }
            }
        }
    }
}

// Use normal version string for new releases and commitHash for other builds
if (projectEnvironment.canPublish) {
    project.version = projectEnvironment.version.get().toString()
} else {
    project.version = "${projectEnvironment.version.get()}_${projectEnvironment.commitHash}"
}

project.group = "io.github.lajczik"

base {
    archivesName.set("JDA-Enhanced")
}

val examples = sourceSets.create("examples") {
    java.srcDir("src/examples/java")
    compileClasspath += sourceSets["main"].output
    runtimeClasspath += sourceSets["main"].output
}

java {
    withJavadocJar()
    withSourcesJar()

    toolchain {
        languageVersion.set(exampleJavaVersion)
    }
}


////////////////////////////////////
//                                //
//    Dependency Configuration    //
//                                //
////////////////////////////////////

val currentJavaVersion = JavaVersion.current().majorVersion

val mockitoAgent = configurations.create("mockitoAgent")

val examplesCompileOnly = configurations.getByName("examplesCompileOnly") {
    extendsFrom(configurations.compileOnly.get())
}

val examplesImplementation = configurations.getByName("examplesImplementation") {
    extendsFrom(configurations.implementation.get())
}

repositories {
    mavenCentral()
}

dependencies {
    /* ABI dependencies */

    //Code safety
    compileOnly(libs.spotbugs)
    compileOnly(libs.jetbrains.annotations)
    compileOnly(libs.errorprone.annotations)

    //Logger
    api(libs.slf4j)

    //Web Connection Support
    api(libs.reactor.netty.http) {
        exclude(group = "io.netty", module = "netty-codec-http3")
        exclude(group = "io.netty", module = "netty-codec-native-quic")
        exclude(group = "io.netty", module = "netty-resolver-dns-native-macos")
        exclude(group = "io.netty", module = "netty-resolver-dns-classes-macos")
    }
    api(libs.netty.codec.http)
    api(libs.netty.handler)
    api(libs.netty.codec.classes.quic)
    api(libs.netty.transport.classes.epoll)
    api(variantOf(libs.netty.transport.native.epoll) { classifier("linux-x86_64") })
    api(variantOf(libs.netty.transport.native.epoll) { classifier("linux-aarch_64") })
    compileOnly(libs.netty.transport.classes.kqueue)

    //Decompression Support
    api(libs.zstd)

    //Opus library support
    api(libs.opus)

    //we use this only together with opus-java
    // if that dependency is excluded it also doesn't need jna anymore
    // since jna is a transitive runtime dependency of opus-java we don't include it explicitly as dependency
    compileOnly(libs.jna)

    /* Internal dependencies */

    //General Utility
    api(libs.fastutil)
    api(libs.nanojson)
    compileOnly(libs.bundles.jackson3)
    compileOnly(libs.bundles.jackson2)

    //Audio crypto libraries
    implementation(libs.tink) {
        exclude(group = "com.google.protobuf", module = "protobuf-java")
        exclude(group = "com.google.code.gson", module = "gson")
        exclude(group = "com.google.code.findbugs", module = "jsr305")
        exclude(group = "com.google.errorprone", module = "error_prone_annotations")
    }

    examplesImplementation(libs.jdave)

    testImplementation(libs.bundles.junit)
    testImplementation(libs.bundles.jackson3)
    testImplementation(libs.bundles.jackson2)
    testImplementation(libs.reflections)
    testImplementation(libs.mockito)
    testImplementation(libs.assertj)
    testImplementation(libs.commons.lang3)
    testImplementation(libs.logback.classic)
    testImplementation(libs.archunit)
    testImplementation(libs.jetbrains.annotations)

    mockitoAgent(libs.mockito) {
        isTransitive = false
    }

    // Linting & Formatting
    errorprone(libs.errorprone.core)
}

fun isNonStable(version: String): Boolean {
    val stableKeyword = listOf("RELEASE", "FINAL", "GA").any { version.uppercase().contains(it) }
    val regex = "^[0-9,.v-]+(-r)?$".toRegex()
    val isStable = stableKeyword || regex.matches(version)
    return isStable.not()
}

versionCatalogUpdate {
    versionSelector(VersionSelectors.STABLE)
}


////////////////////////////////////
//                                //
//      Formatting & Linting      //
//                                //
////////////////////////////////////

spotless {
    encoding("UTF-8")
    lineEndings = LineEnding.GIT_ATTRIBUTES_FAST_ALLSAME

    kotlinGradle {
        target("*.gradle.kts", "buildSrc/*.gradle.kts", "buildSrc/src/**/*.kt*")

        trimTrailingWhitespace()
        leadingTabsToSpaces()
    }

    java {
        palantirJavaFormat("2.84.0")
                .formatJavadoc(false)

        val copyrightHeader = file("gradle/copyright-header.txt")
                .readText(Charsets.UTF_8)
                .trim()
                .prependIndent(" * ")

        licenseHeader("/*\n$copyrightHeader\n */\n\n")

        target("src/**/*.java")

        removeUnusedImports()
        importOrder("", "java", "javax", "\\#")
        trimTrailingWhitespace()
    }
}

val enableErrorpronePatching = tasks.register("enableErrorpronePatching") {
    group = "verification"

    doFirst {
        tasks.withType<JavaCompile>().configureEach {
            options.errorprone {
                errorproneArgs.add("-XepPatchChecks:MissingOverride")
                errorproneArgs.add("-XepPatchLocation:IN_PLACE")
            }
        }
    }
}

tasks.register("format") {
    group = "verification"
    dependsOn(tasks.named("spotlessApply"))
}

val checkFormat = tasks.register("checkFormat") {
    group = "verification"
    dependsOn(tasks.named("spotlessCheck"))
}

tasks.named("check").configure {
    dependsOn(checkFormat)
}

val versionCatalogFile = file("gradle/libs.versions.toml")
tasks.named("versionCatalogFormat").configure {
    inputs.file(versionCatalogFile)
    outputs.file(versionCatalogFile)
}

////////////////////////////////////
//                                //
//    Build Task Configuration    //
//                                //
////////////////////////////////////

val jar = tasks.getByName<Jar>("jar") {
    archiveBaseName.set(project.name)
    manifest.attributes("Implementation-Version" to project.version, "Automatic-Module-Name" to "net.dv8tion.jda")
}

val shadowJar = tasks.getByName<ShadowJar>("shadowJar") {
    archiveClassifier.set("withDependencies")
    exclude("*.pom")
    exclude("**/*.kotlin_metadata")
    exclude("**/*.kotlin_builtins")
    exclude("META-INF/*.kotlin_module")
    exclude("META-INF/maven/**")
    exclude("META-INF/proguard/**")
}

val noOpusJar = tasks.register<ShadowJar>("noOpusJar") {
    dependsOn(shadowJar)
    archiveClassifier.set(shadowJar.archiveClassifier.get() + "-no-opus")

    from(sourceSets["main"].output)
    applyOpusExclusions(artifactFilters)
    exclude("**/*.kotlin_metadata")
    exclude("**/*.kotlin_builtins")
    exclude("META-INF/*.kotlin_module")
    exclude("META-INF/maven/**")
    exclude("META-INF/proguard/**")
    manifest.from(jar.manifest)
}

val minimalJar = tasks.register<ShadowJar>("minimalJar") {
    dependsOn(shadowJar)
    minimize()
    archiveClassifier.set(shadowJar.archiveClassifier.get() + "-min")

    from(sourceSets["main"].output)
    applyAudioExclusions(artifactFilters)
    applyNettyExclusions(artifactFilters)
    exclude("**/*.kotlin_metadata")
    exclude("**/*.kotlin_builtins")
    exclude("META-INF/*.kotlin_module")
    exclude("META-INF/maven/**")
    exclude("META-INF/proguard/**")

    manifest.from(jar.manifest)
}

tasks.withType<ShadowJar>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.WARN
    mergeServiceFiles()

    exclude("**/LICENSE*")
    exclude("**/LICENCE*")
    exclude("**/README*")
    exclude("**/NOTICE*")


    if (this != shadowJar) {
        manifest.from(shadowJar.manifest)
        configurations = shadowJar.configurations
        excludes.addAll(shadowJar.excludes)
    }
}

val javadoc = tasks.getByName<Javadoc>("javadoc") {
    isFailOnError = projectEnvironment.isGithubAction

    (options as? StandardJavadocDocletOptions)?.apply {
        memberLevel = JavadocMemberLevel.PUBLIC
        encoding = "UTF-8"
        locale = "en_US"

        author()
        tags("incubating:a:Incubating:")
        links("https://docs.oracle.com/en/java/javase/$currentJavaVersion/docs/api/", "https://netty.io/4.2/api/")

        addStringOption("-link-modularity-mismatch", "info")
        addStringOption("-release", libraryJavaVersion.asInt().toString())
        addBooleanOption("-syntax-highlight", true)
        addBooleanOption("Xdoclint:all,-missing", true)

        overview = "$projectDir/overview.html"
    }

    exclude {
        it.file.absolutePath.contains("internal", ignoreCase = false)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.isIncremental = true

    options.compilerArgs.addAll(listOf(
            "-Werror",
            "-Xlint:all,-try,-varargs,-serial,-deprecation,-this-escape"
    ))

    options.errorprone {
        disable(
                "AssignmentExpression",
                "ByteBufferBackingArray",
                "CheckReturnValue",
                "DoubleCheckedLocking",
                "EffectivelyPrivate",
                "EmptyCatch",
                "EnumOrdinal",
                "Finalize",
                "FutureReturnValueIgnored",
                "InvalidBlockTag",
                "JavaDurationGetSecondsToToSeconds",
                "JavaTimeDefaultTimeZone",
                "MathAbsoluteNegative",
                "MixedMutabilityReturnType",
                "OperatorPrecedence",
                "PatternMatchingInstanceof",
                "StatementSwitchToExpressionSwitch",
                "StringSplitter",
                "ParameterName",
                "StringConcatToTextBlock",
                "TypeParameterUnusedInFormals",
                "UnnecessaryLambda",
                "UnusedMethod",
        )
    }

    mustRunAfter(enableErrorpronePatching)
}

val compileJava = tasks.getByName<JavaCompile>("compileJava") {
    options.release = libraryJavaVersion.asInt()
}

tasks.named<JavaCompile>("compileExamplesJava") {
    options.errorprone {
        disableAllChecks.set(true)
    }
}

tasks.build.configure {
    dependsOn(jar)
    dependsOn(shadowJar)
    dependsOn(noOpusJar)
    dependsOn(minimalJar)

    jar.mustRunAfter(tasks.clean)
}


////////////////////////////////////
//                                //
//       Test Configuration       //
//                                //
////////////////////////////////////


tasks.register<Test>("updateTestSnapshots") {
    group = "verification"
    useJUnitPlatform()

    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath

    systemProperty("updateSnapshots", "true")
}

tasks.test {
    useJUnitPlatform()
    failFast = false

    jvmArgs(
            "-javaagent:${mockitoAgent.asPath}",
            // https://github.com/raphw/byte-buddy/issues/1803
            "-Dnet.bytebuddy.safe=true"
    )

    testLogging {
        events("failed")
    }
    reports {
        junitXml.required = projectEnvironment.isGithubAction
        html.required = true
    }
}

val verifyBytecodeVersion = tasks.register<VerifyBytecodeVersion>("verifyBytecodeVersion") {
    group = "verification"

    expectedMajorVersion = 65
    classes.from(compileJava.outputs.files.asFileTree.matching {
        include("**/*.class")
    })
}

compileJava.finalizedBy(verifyBytecodeVersion)

tasks.withType<Test>().configureEach {
    systemProperties.putAll(mapOf(
            "user.timezone" to "GMT",
            "user.language" to "en",
            "user.country" to "US",
            "file.encoding" to "utf-8",
    ))
}


////////////////////////////////////
//                                //
//    Publishing And Signing      //
//                                //
////////////////////////////////////


// Generate pom file for maven central

fun MavenPom.populate() {
    packaging = "jar"
    name.set(project.name)
    description.set("Lightweight java wrapper for the popular chat & VOIP service: Discord https://discord.com")
    url.set("https://github.com/lajczik/JDA-Enhanced")
    scm {
        url.set("https://github.com/lajczik/JDA-Enhanced")
        connection.set("scm:git:git://github.com/lajczik/JDA-Enhanced.git")
        developerConnection.set("scm:git:ssh:git@github.com/lajczik/JDA-Enhanced.git")
    }
    licenses {
        license {
            name.set("The Apache Software License, Version 2.0")
            url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            distribution.set("repo")
        }
    }
    developers {
        developer {
            id.set("lychee")
            name.set("lajczik")
            email.set("lajczik@gmail.com")
        }
        developer {
            id.set("Minn")
            name.set("Florian Spieß")
            email.set("business@minn.dev")
        }
        developer {
            id.set("DV8FromTheWorld")
            name.set("Austin Keener")
            email.set("keeneraustin@yahoo.com")
        }
    }
}

shadow {
    addShadowVariantIntoJavaComponent = false
}

val mavenCentralUsername: String? = System.getenv("MAVENCENTRAL_USERNAME")?.takeIf { it.isNotBlank() }
val mavenCentralPassword: String? = System.getenv("MAVENCENTRAL_TOKEN")?.takeIf { it.isNotBlank() }
val gpgSecretKey: String? = System.getenv("GPG_SECRET_KEY")?.takeIf { it.isNotBlank() }
val gpgPassphrase: String? = System.getenv("GPG_PASSPHRASE")?.takeIf { it.isNotBlank() }

val stagingDirectory = layout.buildDirectory.dir("staging-deploy").get()

publishing {
    publications {
        register<MavenPublication>("Release") {
            from(components["java"])

            artifactId = project.name
            groupId = project.group as String
            version = project.version as String

            pom.populate()
        }
    }
}

if (gpgSecretKey != null) {
    signing {
        useInMemoryPgpKeys(gpgSecretKey, gpgPassphrase ?: "")
        sign(publishing.publications)
    }
}

nmcpAggregation {
    localRepository {
        name = "staging-deploy"
        path = stagingDirectory.asFile.path
    }

    centralPortal {
        username.set(mavenCentralUsername)
        password.set(mavenCentralPassword)
    }
}
