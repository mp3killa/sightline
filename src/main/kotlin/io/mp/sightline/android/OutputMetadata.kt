package io.mp.sightline.android

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * What AGP recorded about a build output. Every field is nullable: this is read defensively from a file
 * we don't own, and one missing key must cost that key rather than the whole record.
 */
data class OutputMetadata(
    val metadataVersion: Int?,
    val applicationId: String?,
    val variantName: String?,
    val versionCode: Int?,
    val versionName: String?,
    val minSdkForDexing: Int?,
    val outputFile: String?,
    /** True when [metadataVersion] is past anything we've seen — surfaced as a note, not a refusal. */
    val unrecognisedVersion: Boolean = false,
) {
    val isEmpty: Boolean get() = applicationId == null && variantName == null && versionName == null
}

/**
 * Parses AGP's `output-metadata.json` — the tier-2 rung of the fact ladder, and the discovery that makes
 * the CLI-first strategy work rather than merely survive (docs/ANDROID.md §1.2).
 *
 * AGP writes this next to every build output:
 * `app/build/intermediates/apk/<flavour>/<type>/output-metadata.json`. It carries the applicationId with
 * the flavour suffix **already applied**, the variant name, version code/name, and the dexing min SDK —
 * most of the context strip, from a file, with no Android Studio API and no Gradle invocation.
 *
 * The tradeoff it cannot escape: it describes the **last build**, not the current selection. Callers must
 * tag it [FactTier.BUILD_OUTPUT] so the user sees "(last build)" and can tell a stale variant from a
 * current one.
 */
object OutputMetadataParser {

    /**
     * Metadata versions whose shape we've actually seen. A newer one is parsed anyway — the fields have
     * been stable across every bump so far, and refusing to read a file we can probably read would drop
     * the user to a worse tier for no reason — but it is flagged, so a note can say the format moved.
     */
    private const val KNOWN_MAX_VERSION = 3

    fun parse(json: String): OutputMetadata? {
        val root = try {
            JsonParser.parseString(json)?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        } catch (e: Exception) {
            return null
        }

        val version = root.intOrNull("version")
        // `elements` is an array of outputs — one per split/ABI. The unfiltered SINGLE output is the one
        // that matches what a plain install deploys, so prefer it and fall back to the first.
        val elements = root.get("elements")?.takeIf { it.isJsonArray }?.asJsonArray
        val element = elements?.firstOrNull { e ->
            e.isJsonObject && e.asJsonObject.get("type")?.asStringOrNull() == "SINGLE"
        }?.asJsonObject ?: elements?.firstOrNull { it.isJsonObject }?.asJsonObject

        val meta = OutputMetadata(
            metadataVersion = version,
            applicationId = root.stringOrNull("applicationId"),
            variantName = root.stringOrNull("variantName"),
            versionCode = element?.intOrNull("versionCode"),
            versionName = element?.stringOrNull("versionName"),
            minSdkForDexing = root.intOrNull("minSdkVersionForDexing"),
            outputFile = element?.stringOrNull("outputFile"),
            unrecognisedVersion = version != null && version > KNOWN_MAX_VERSION,
        )
        return meta.takeUnless { it.isEmpty }
    }

    /**
     * The variant a `merged_manifest/<variant>/…` or `apk/<flavour>/<type>/…` path refers to.
     *
     * Worth having because the directory layout is itself a fact: the *names* of those directories are
     * the variants that have been built, which is how the strip can offer a variant list without a Gradle
     * invocation. Returns null for a path that doesn't have the shape, rather than guessing at a segment.
     */
    fun variantFromPath(path: String): String? {
        val segments = path.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        val i = segments.indexOfFirst { it == "merged_manifest" || it == "merged_manifests" || it == "packaged_manifests" }
        if (i >= 0 && i + 1 < segments.size) return segments[i + 1].takeIf { it.isNotBlank() }
        return null
    }

    // Gson accessors that treat a wrong-typed value as absent rather than throwing — the file is
    // AGP's, not ours, and a type change must not take the panel down with it.
    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.asStringOrNull()?.takeIf { it.isNotBlank() }

    private fun JsonObject.intOrNull(key: String): Int? = try {
        get(key)?.takeIf { it.isJsonPrimitive }?.asInt
    } catch (e: Exception) {
        null
    }

    private fun com.google.gson.JsonElement.asStringOrNull(): String? = try {
        if (isJsonPrimitive) asString else null
    } catch (e: Exception) {
        null
    }
}
