import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit

plugins {
    application
}

group = "app"
version = "0.1.0"

val minecraftClientJar = providers.gradleProperty("minecraftClientJar")
    .orElse(providers.environmentVariable("MINECRAFT_CLIENT_JAR"))
    .orElse(
        System.getenv("APPDATA")?.let {
            File(it, ".minecraft/versions/26.1.2/26.1.2.jar").absolutePath
        } ?: ""
    )
val minecraftClientJarFile = file(minecraftClientJar.get())
val minecraftLibraryFiles = System.getenv("APPDATA")?.let { appData ->
    listOf(
        "com/mojang/brigadier/1.3.10/brigadier-1.3.10.jar",
        "org/jspecify/jspecify/1.0.0/jspecify-1.0.0.jar",
        "it/unimi/dsi/fastutil/8.5.18/fastutil-8.5.18.jar",
        "org/lwjgl/lwjgl/3.4.1/lwjgl-3.4.1.jar",
        "org/lwjgl/lwjgl-glfw/3.4.1/lwjgl-glfw-3.4.1.jar",
        "org/joml/joml/1.10.8/joml-1.10.8.jar",
        "com/mojang/datafixerupper/9.0.19/datafixerupper-9.0.19.jar",
        // The attack hook sits in the connection's Netty pipeline.
        "io/netty/netty-transport/4.2.7.Final/netty-transport-4.2.7.Final.jar",
        "io/netty/netty-common/4.2.7.Final/netty-common-4.2.7.Final.jar",
        "io/netty/netty-buffer/4.2.7.Final/netty-buffer-4.2.7.Final.jar",
        // Hide other clients rewrites the HUD drawing class with the ASM Fabric Loader ships.
        "org/ow2/asm/asm/9.10.1/asm-9.10.1.jar",
    ).map { File(appData, ".minecraft/libraries/$it") }
} ?: emptyList()
// The HUD registers through Fabric API, whose modules are nested jars inside the mod jar.
val fabricApiJar = System.getenv("APPDATA")?.let { appData ->
    File(appData, ".minecraft/mods").listFiles { file ->
        file.name.startsWith("fabric-api-") && file.name.endsWith("+26.1.2.jar")
    }?.firstOrNull()
} ?: File("fabric-api-for-26.1.2-not-found.jar")
val missingMinecraftFiles = (listOf(minecraftClientJarFile, fabricApiJar) + minecraftLibraryFiles)
    .filterNot { it.isFile }

// Without these, the in-game classes are skipped and the agent JAR cannot open the GUI.
val appSourceDirs = listOf(file("src/main/java/app")) +
    if (missingMinecraftFiles.isEmpty()) listOf(file("src/minecraft/26.1.2/java/app")) else emptyList()
if (missingMinecraftFiles.isEmpty()) {
    dependencies.add("compileOnly", files(minecraftClientJarFile))
    dependencies.add("compileOnly", files(minecraftLibraryFiles))
    dependencies.add("compileOnly", zipTree(fabricApiJar).matching {
        include("META-INF/jars/fabric-rendering-v1-*.jar")
        // Event, which the world drawing hook (Wheels) registers through.
        include("META-INF/jars/fabric-api-base-*.jar")
    })
}

// A running game cannot replace classes it has already loaded, so each changed build must
// use new class names to be injected next to the old one. The sources stay in package
// "app"; every build compiles them into app.b<tag>, where the tag is a hash of the sources.
// Unchanged sources keep their tag, any change gets a new one, and nothing is renamed by hand.
val buildTag: String = run {
    val digest = MessageDigest.getInstance("SHA-256")
    appSourceDirs.flatMap { dir -> dir.listFiles { file -> file.extension == "java" }.orEmpty().toList() }
        .sortedBy { it.name }
        .forEach { source ->
            digest.update(source.name.toByteArray())
            digest.update(source.readBytes())
        }
    "b" + digest.digest().take(4).joinToString("") { "%02x".format(it) }
}
val versionedSourceDir = layout.buildDirectory.dir("generated/versioned-sources")
val versionSources = tasks.register<Sync>("versionSources") {
    into(versionedSourceDir)
    from(appSourceDirs) {
        include("*.java")
        into("app/$buildTag")
        filter { line -> if (line == "package app;") "package app.$buildTag;" else line }
    }
}
sourceSets.main {
    java.setSrcDirs(listOf(versionedSourceDir))
}
tasks.named("compileJava") {
    dependsOn(versionSources)
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

repositories {
    mavenCentral()
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("--add-modules=jdk.attach")
}

tasks.jar {
    archiveFileName.set("Uranium-$buildTag.jar")
    doFirst {
        if (missingMinecraftFiles.isNotEmpty()) {
            throw GradleException(
                "Missing Minecraft files, so the in-game GUI would not be included:\n" +
                    missingMinecraftFiles.joinToString("\n") { "  $it" } +
                    "\nLaunch Minecraft 26.1.2 once, or pass -PminecraftClientJar=PATH."
            )
        }
    }
    manifest {
        attributes(
            "Agent-Class" to "app.$buildTag.MinecraftGuiAgent",
            // Hide other clients adds a check to a class the game has already loaded.
            "Can-Retransform-Classes" to "true",
            // The same jar opens the injector window, which then injects itself.
            "Main-Class" to "app.$buildTag.Main",
            // When it was built: the launcher runs the newest build, and the Update tab
            // compares this with the latest release on GitHub.
            "Build-Time" to Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
            "Build-Tag" to buildTag
        )
    }
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("app.$buildTag.Main")
    applicationDefaultJvmArgs = listOf("--add-modules=jdk.attach")
}

// Deletes older builds' injector jars after every build, not only when the jar is rebuilt,
// so they go as soon as nothing holds them. One still loaded by a running game is locked on
// Windows, so it stays until the game closes and goes on the next build after that.
val deleteOldJars = tasks.register("deleteOldJars") {
    val libs = layout.buildDirectory.dir("libs")
    val current = "Uranium-$buildTag.jar"
    outputs.upToDateWhen { false }
    doLast {
        libs.get().asFile.listFiles { file ->
            (file.name.startsWith("Uranium-") || file.name.startsWith("minecraft-gui-injector-"))
                && file.name.endsWith(".jar") && file.name != current
        }?.forEach { it.delete() }
    }
}
tasks.jar {
    finalizedBy(deleteOldJars)
}

// A zip to hand to someone else: the jar, its own Java 25 in runtime/ (so it works on a PC
// where Start.bat finds no other Java), Start.bat and a short how-to.
// `gradle shareZip` writes build/distributions/Uranium.zip. The Java is the Minecraft
// Launcher's own (Microsoft Build of OpenJDK, GPLv2 with the Classpath Exception, its
// licenses in runtime/legal); override with -PuraniumRuntime=PATH to a Java 25 folder.
val uraniumRuntime = file(providers.gradleProperty("uraniumRuntime")
    .orElse(providers.gradleProperty("org.gradle.java.installations.paths")).get())
tasks.register<Zip>("shareZip") {
    archiveFileName.set("Uranium.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    into("Uranium") {
        from(tasks.jar) { rename { "uranium.jar" } }
        from("share")
        if (uraniumRuntime.resolve("bin/javaw.exe").isFile) {
            from(uraniumRuntime) { into("runtime") }
        }
    }
}

// Uranium.exe: a folder with the exe, its own Java (only the parts it needs) and the
// build, made by jpackage (from a JDK 25, Eclipse Adoptium's by default; pass
// -Pjpackage=PATH to jpackage.exe otherwise). The exe starts a tiny launcher, which runs the
// newest build it finds: the one packed in app/, or one the Update tab downloaded into
// builds/. `gradle packageZip` writes dist/Uranium (the folder) and dist/Uranium.zip.
val launcherSource = sourceSets.create("launcher") {
    java.setSrcDirs(listOf("src/launcher/java"))
}
val launcherJar = tasks.register<Jar>("launcherJar") {
    from(launcherSource.output)
    archiveFileName.set("uranium-launcher.jar")
    destinationDirectory.set(layout.buildDirectory.dir("launcher"))
    manifest { attributes("Main-Class" to "uranium.Launcher") }
}
val jpackageTool: File? = providers.gradleProperty("jpackage").map { file(it) }.orNull
    ?: File("C:/Program Files/Eclipse Adoptium").listFiles()?.sortedDescending()
        ?.map { File(it, "bin/jpackage.exe") }?.firstOrNull { it.isFile }
val packageApp = tasks.register("packageApp") {
    dependsOn(tasks.jar, launcherJar)
    val jar = tasks.jar.flatMap { it.archiveFile }
    val launcher = launcherJar.flatMap { it.archiveFile }
    doLast {
        val tool = jpackageTool ?: throw GradleException("No jpackage found: install a JDK 25 or pass -Pjpackage=PATH")
        val input = layout.buildDirectory.dir("jpackage-input").get().asFile
        input.deleteRecursively()
        input.mkdirs()
        jar.get().asFile.copyTo(File(input, jar.get().asFile.name))
        launcher.get().asFile.copyTo(File(input, "uranium-launcher.jar"))
        val dist = file("dist")
        File(dist, "Uranium").deleteRecursively()
        dist.mkdirs()
        // Builder.exe next to Uranium.exe: the same Java and build, opened on the Builder
        val builderProps = layout.buildDirectory.file("jpackage-builder.properties").get().asFile
        builderProps.writeText(listOf("arguments=builder", "description=Uranium Builder",
            "icon=" + file("packaging/uranium.ico").absolutePath.replace('\\', '/')).joinToString(System.lineSeparator()))
        val command = listOf(tool.absolutePath,
            "--add-launcher", "Builder=" + builderProps.absolutePath,
            "--type", "app-image",
            "--name", "Uranium",
            "--input", input.absolutePath,
            "--main-jar", "uranium-launcher.jar",
            "--main-class", "uranium.Launcher",
            "--dest", dist.absolutePath,
            "--icon", file("packaging/uranium.ico").absolutePath,
            "--add-modules", "java.base,java.desktop,java.net.http,jdk.attach,java.logging",
            "--java-options", "--add-modules=jdk.attach",
            "--app-version", "1.0." + (System.currentTimeMillis() / 60_000 % 60_000),
            "--vendor", "Uranium",
            "--description", "Uranium injector")
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) {
            throw GradleException("jpackage failed: $output")
        }
        file("share/HOW-TO-USE.txt").copyTo(File(dist, "Uranium/HOW-TO-USE.txt"), overwrite = true)
    }
}
tasks.register<Zip>("packageZip") {
    dependsOn(packageApp)
    archiveFileName.set("Uranium.zip")
    destinationDirectory.set(file("dist"))
    from(file("dist/Uranium")) { into("Uranium") }
    doLast {
        // The files to attach to a GitHub release: the build, plus the same build under its
        // old name, minecraft-gui-injector-<tag>.jar. Installs from before the rename only look
        // for that name, so attach both to the next releases; once players have updated past
        // the rename the old-name copy can go.
        val jar = tasks.jar.get().archiveFile.get().asFile
        val dist = file("dist")
        dist.listFiles { f -> f.name.endsWith(".jar") }?.forEach { it.delete() }
        jar.copyTo(File(dist, jar.name), overwrite = true)
        jar.copyTo(File(dist, "minecraft-gui-injector-$buildTag.jar"), overwrite = true)
    }
}

tasks.named<JavaExec>("run") {
    dependsOn(tasks.jar)
}

tasks.test {
    useJUnitPlatform()
}
