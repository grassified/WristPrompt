import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

val legalAssets = objects.directoryProperty().value(layout.buildDirectory.dir("generated/distributionAssets"))
extra["wristpromptDistributionAssets"] = legalAssets
val runtimeArtifacts = providers.provider { configurations.getByName("releaseRuntimeClasspath") }
val appVersion = extra["wristpromptVersionName"] as String
val legalDocuments = listOf("LICENSE", "LICENSE_EXCEPTION.md", "THIRD_PARTY_NOTICES.md", "legal/APACHE-2.0.txt", "legal/RATEX.txt", "legal/NOTO_MATH.txt", "legal/RATEX_NATIVE.txt")
val generateDistributionNotices = tasks.register("generateDistributionNotices") {
    inputs.files(runtimeArtifacts)
    inputs.property("appVersion", appVersion)
    inputs.files(legalDocuments.map { rootProject.file(it) })
    outputs.dir(legalAssets)
    doLast {
        val directory = legalAssets.get().dir("legal").asFile
        directory.deleteRecursively()
        check(directory.mkdirs())
        legalDocuments.forEach { path -> rootProject.file(path).copyTo(directory.resolve(path.substringAfterLast('/'))) }
        val inventory = StringBuilder("Resolved production artifact notices; archive paths are upstream entries.\n")
        var number = 0
        val legalName = Regex("(?i)(license|notice|copying|third_party_licenses)([._-].*)?")
        fun collect(bytes: ByteArray, source: String, depth: Int = 0) {
            check(depth <= 2)
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!entry.isDirectory && (legalName.matches(name.substringAfterLast('/')) || name.endsWith(".jar"))) {
                        val limit = if (name.endsWith(".jar")) 64 * 1024 * 1024 else 2 * 1024 * 1024
                        val content = zip.readNBytes(limit + 1)
                        check(content.size <= limit) { "Distribution notice archive exceeds the bounded reader" }
                        if (name.endsWith(".jar")) collect(content, "$source!/$name", depth + 1)
                        else {
                            val target = "notice-${(++number).toString().padStart(4, '0')}.txt"
                            directory.resolve(target).writeBytes(content)
                            inventory.append("$target <- $source!/$name\n")
                        }
                    }
                    entry = zip.nextEntry
                }
            }
        }
        runtimeArtifacts.get().incoming.artifacts.artifacts.sortedBy { it.id.componentIdentifier.displayName + it.file.name }.forEach { artifact ->
            val id = artifact.id.componentIdentifier.displayName
            val bytes = artifact.file.readBytes()
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            inventory.append("\n$id  SHA-256 $digest\n")
            collect(bytes, id)
        }
        check(number > 0) { "No upstream notices were collected" }
        directory.resolve("INVENTORY.txt").writeText(inventory.toString())
        val version = appVersion
        directory.resolve("SOURCE.txt").writeText("WristPrompt $version\nGPL-3.0-only with LICENSE_EXCEPTION.md.\n" +
            "Corresponding source and build instructions: https://github.com/grassified/WristPrompt/tree/v$version\n" +
            "Source archive: https://github.com/grassified/WristPrompt/archive/refs/tags/v$version.tar.gz\n" +
            "Third-party artifacts retain their original licenses. See THIRD_PARTY_NOTICES.md, INVENTORY.txt and notice-*.txt.\n")
    }
}
