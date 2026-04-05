package org.nixos.gradle2nix

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.api.internal.artifacts.ivyservice.modulecache.FileStoreAndIndexProvider
import org.gradle.api.invocation.Gradle
import org.gradle.internal.hash.ChecksumService
import org.gradle.internal.operations.BuildOperationDescriptor
import org.gradle.internal.operations.BuildOperationListener
import org.gradle.internal.operations.OperationFinishEvent
import org.gradle.internal.operations.OperationIdentifier
import org.gradle.internal.operations.OperationProgressEvent
import org.gradle.internal.operations.OperationStartEvent
import org.gradle.internal.resource.ExternalResourceReadBuildOperationType
import org.gradle.internal.resource.ExternalResourceReadMetadataBuildOperationType
import org.nixos.gradle2nix.model.DependencyCoordinates
import org.nixos.gradle2nix.model.DependencySet
import org.nixos.gradle2nix.model.impl.DefaultDependencyCoordinates
import org.nixos.gradle2nix.model.impl.DefaultDependencySet
import org.nixos.gradle2nix.model.impl.DefaultResolvedArtifact
import org.nixos.gradle2nix.model.impl.DefaultResolvedDependency
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

interface DependencyExtractorApplier {
    fun apply(
        gradle: Gradle,
        extractor: DependencyExtractor,
    )
}

class DependencyExtractor : BuildOperationListener {
    private val urls = ConcurrentHashMap<String, Unit>()

    override fun started(
        buildOperation: BuildOperationDescriptor,
        startEvent: OperationStartEvent,
    ) {}

    override fun progress(
        operationIdentifier: OperationIdentifier,
        progressEvent: OperationProgressEvent,
    ) {}

    override fun finished(
        buildOperation: BuildOperationDescriptor,
        finishEvent: OperationFinishEvent,
    ) {
        when (val details = buildOperation.details) {
            is ExternalResourceReadBuildOperationType.Details -> urls.computeIfAbsent(details.location) { Unit }
            is ExternalResourceReadMetadataBuildOperationType.Details -> urls.computeIfAbsent(details.location) { Unit }
            else -> null
        } ?: return
    }

    fun buildDependencySet(
        cacheAccess: GradleCacheAccess,
        checksumService: ChecksumService,
        fileStoreAndIndexProvider: FileStoreAndIndexProvider,
    ): DependencySet {
        val files = mutableMapOf<DependencyCoordinates, MutableMap<File, String>>()
        val mappings = mutableMapOf<DependencyCoordinates, Map<String, String>>()

        // Partition URLs: those found in the artifact cache (files-2.1) and those not.
        // Metadata files (POM, .module) for conflict-losing versions are stored in
        // metadata-2.107 and never appear in externalResourceIndex. We handle them in
        // a second pass using a name→group lookup built from the first pass.
        val missedUrls = mutableListOf<String>()

        cacheAccess.useCache {
            for ((url, _) in urls) {
                // Skip file:// URLs — local Maven repos (e.g. npm packages that bundle
                // a local_repo/) are not recorded in Gradle's external resource cache
                // and cannot be fetched via the network, so they have no place in the
                // lock file. The offline init.gradle is patched to leave file:// repos
                // untouched so Gradle can find them from the source tree at build time.
                if (url.startsWith("file:")) continue
                val cached = fileStoreAndIndexProvider.externalResourceIndex.lookup(url)
                if (cached != null) {
                    cached.cachedFile?.let { file ->
                        cachedComponentId(file)?.let { componentId ->
                            files.getOrPut(componentId, ::mutableMapOf)[file] = url
                            if (file.extension == "module") {
                                parseFileMappings(file)?.let {
                                    mappings[componentId] = it
                                }
                            }
                        }
                    }
                } else {
                    missedUrls.add(url)
                }
            }
        }

        // Second pass: for metadata URLs not in the artifact cache, derive coordinates
        // from the URL using the name→group map built above, then download the file.
        // This captures POM/module files for version-conflict-losing artifacts.
        if (missedUrls.isNotEmpty()) {
            val nameToGroup: Map<String, String> = files.keys.associate { it.artifact to it.group }
            for (url in missedUrls) {
                val coords = coordinatesFromUrl(url, nameToGroup) ?: continue
                val file = downloadToTempFile(url) ?: continue
                files.getOrPut(coords, ::mutableMapOf)[file] = url
                if (file.extension == "module") {
                    parseFileMappings(file)?.let { mappings[coords] = it }
                }
            }
        }

        return DefaultDependencySet(
            dependencies =
                buildList {
                    for ((componentId, componentFiles) in files) {
                        add(
                            DefaultResolvedDependency(
                                componentId,
                                buildList {
                                    val remoteMappings = mappings[componentId]
                                    for ((file, url) in componentFiles) {
                                        add(
                                            DefaultResolvedArtifact(
                                                remoteMappings?.get(file.name) ?: file.name,
                                                checksumService.sha256(file).toString(),
                                                url,
                                            ),
                                        )
                                    }
                                },
                            ),
                        )
                    }
                },
        )
    }
}

private fun <T> buildList(block: MutableList<T>.() -> Unit): List<T> = mutableListOf<T>().apply(block).toList()

/** Download [url] to a temp file, returning the file, or null if the download fails. */
private fun downloadToTempFile(url: String): File? =
    try {
        val conn = URI(url).toURL().openConnection()
        conn.connectTimeout = 30_000
        conn.readTimeout = 30_000
        val ext = url.substringAfterLast('.', "tmp")
        val tmp = File.createTempFile("gradle2nix-", ".$ext")
        tmp.deleteOnExit()
        conn.getInputStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        tmp
    } catch (_: Throwable) {
        null
    }

private fun cachedComponentId(file: File): DependencyCoordinates? {
    val parts = file.invariantSeparatorsPath.split('/')
    if (parts.size < 6) return null
    if (parts[parts.size - 6] != "files-2.1") return null
    return parts
        .dropLast(2)
        .takeLast(3)
        .joinToString(":")
        .let(DefaultDependencyCoordinates::parse)
}

/**
 * Derive Maven coordinates from a metadata URL like:
 *   https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.0.21/kotlin-stdlib-2.0.21.pom
 *
 * Maven URL layout: .../<group-path>/<name>/<version>/<filename>
 * We extract <name> and <version> from the path (last 3 segments before the filename).
 * The group is looked up from [nameToGroup] — a map of artifact names to groups built from
 * artifacts we already resolved via the files-2.1 cache. This avoids the ambiguity of
 * separating the repo root prefix from the group path in the URL.
 *
 * Returns null if the URL doesn't look like a Maven artifact or the name is unknown.
 */
private fun coordinatesFromUrl(url: String, nameToGroup: Map<String, String>): DependencyCoordinates? {
    return try {
        val segments = URI(url).path.trimEnd('/').split('/')
        if (segments.size < 4) null
        else {
            val version = segments[segments.size - 2]
            val name = segments[segments.size - 3]
            val filename = segments.last()
            // Sanity check: filename must start with "<name>-<version>"
            if (!filename.startsWith("$name-$version")) null
            else {
                val group = nameToGroup[name] ?: null
                if (group == null) null
                else DefaultDependencyCoordinates.parse("$group:$name:$version")
            }
        }
    } catch (_: Throwable) {
        null
    }
}

@OptIn(ExperimentalSerializationApi::class)
private fun parseFileMappings(file: File): Map<String, String>? =
    try {
        Json
            .decodeFromStream<JsonObject>(file.inputStream())
            .jsonObject["variants"]
            ?.jsonArray
            ?.flatMap { it.jsonObject["files"]?.jsonArray ?: emptyList() }
            ?.map { it.jsonObject }
            ?.mapNotNull {
                val name = it["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val url = it["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                if (name != url) name to url else null
            }?.toMap()
            ?.takeUnless { it.isEmpty() }
    } catch (e: Throwable) {
        null
    }
