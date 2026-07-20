package io.mp.claudecodepanel.android

/** One declared component. */
data class ManifestComponent(
    val kind: Kind,
    val name: String,
    val exported: Boolean?,
    val permission: String?,
    val intentActions: List<String> = emptyList(),
    val deepLinks: List<String> = emptyList(),
    val foregroundServiceType: String? = null,
    val line: Int = 0,
) {
    enum class Kind(val label: String) {
        ACTIVITY("Activity"), SERVICE("Service"), RECEIVER("Receiver"), PROVIDER("Provider")
    }

    /** Exported and reachable without a permission — the shape that matters for the audit. */
    val isOpenlyExported: Boolean get() = exported == true && permission.isNullOrBlank()
}

data class ManifestModel(
    val packageName: String? = null,
    val permissions: List<String> = emptyList(),
    val components: List<ManifestComponent> = emptyList(),
    val usesCleartextTraffic: Boolean? = null,
    val networkSecurityConfig: String? = null,
    val allowBackup: Boolean? = null,
    val dataExtractionRules: String? = null,
    val fullBackupContent: String? = null,
    val queries: List<String> = emptyList(),
    val minSdk: Int? = null,
    val targetSdk: Int? = null,
    /**
     * Whether an `<application>` element is present at all. Without it, application-level checks must
     * stay silent: a fragment with no application element isn't an app with insecure defaults, it is a
     * fragment, and reporting otherwise turns every partial manifest into a false finding.
     */
    val hasApplication: Boolean = false,
)

enum class AuditSeverity { HIGH, MEDIUM, LOW }

data class AuditFinding(
    val severity: AuditSeverity,
    val title: String,
    val detail: String,
    val component: String? = null,
    val line: Int = 0,
    /** What to actually do. Never null — a finding with no action is a complaint. */
    val remedy: String,
)

/**
 * Parses an `AndroidManifest.xml` and audits it.
 *
 * Meant to run against the **merged** manifest from `build/intermediates/merged_manifest/<variant>/`,
 * not the source one, because the merge is where the interesting problems appear: a library contributes
 * an exported receiver, or a permission arrives from a transitive dependency that nothing in your own
 * manifest mentions. Auditing the source manifest would give a clean report on an app that ships an open
 * component.
 *
 * A hand-rolled scan rather than an XML parser, for one reason that matters: **line numbers.** A finding
 * a developer can open beats one they have to search for, and the platform's XML readers don't hand back
 * positions without a lot more machinery. The tradeoff is accepted deliberately, and the parser is
 * correspondingly conservative — an attribute it cannot read becomes null, which the audit treats as
 * "unknown", never as "false".
 */
object ManifestAudit {

    // ---- parsing ----

    fun parse(xml: String): ManifestModel {
        val lines = xml.lines()
        return ManifestModel(
            packageName = attr(xml, "package"),
            permissions = USES_PERMISSION.findAll(xml).map { it.groupValues[1] }.distinct().toList(),
            components = parseComponents(lines),
            usesCleartextTraffic = attr(xml, "android:usesCleartextTraffic")?.toBooleanStrictOrNull(),
            networkSecurityConfig = attr(xml, "android:networkSecurityConfig"),
            allowBackup = attr(xml, "android:allowBackup")?.toBooleanStrictOrNull(),
            dataExtractionRules = attr(xml, "android:dataExtractionRules"),
            fullBackupContent = attr(xml, "android:fullBackupContent"),
            queries = QUERY_PACKAGE.findAll(xml).map { it.groupValues[1] }.distinct().toList(),
            minSdk = attr(xml, "android:minSdkVersion")?.toIntOrNull(),
            targetSdk = attr(xml, "android:targetSdkVersion")?.toIntOrNull(),
            hasApplication = xml.contains("<application"),
        )
    }

    private fun parseComponents(lines: List<String>): List<ManifestComponent> {
        val out = mutableListOf<ManifestComponent>()
        var i = 0
        while (i < lines.size) {
            val kind = when {
                lines[i].contains("<activity") -> ManifestComponent.Kind.ACTIVITY
                lines[i].contains("<service") -> ManifestComponent.Kind.SERVICE
                lines[i].contains("<receiver") -> ManifestComponent.Kind.RECEIVER
                lines[i].contains("<provider") -> ManifestComponent.Kind.PROVIDER
                else -> null
            }
            if (kind == null) { i++; continue }

            val end = elementEnd(lines, i, kind)
            val block = lines.subList(i, minOf(end + 1, lines.size)).joinToString("\n")

            val name = attr(block, "android:name")
            if (name != null) {
                out += ManifestComponent(
                    kind = kind,
                    name = name,
                    exported = attr(block, "android:exported")?.toBooleanStrictOrNull(),
                    permission = attr(block, "android:permission"),
                    intentActions = ACTION.findAll(block).map { it.groupValues[1] }.distinct().toList(),
                    deepLinks = parseDeepLinks(block),
                    foregroundServiceType = attr(block, "android:foregroundServiceType"),
                    line = i + 1,
                )
            }
            i = end + 1
        }
        return out
    }

    /** `<data android:scheme="demo" android:host="delivery"/>` → `demo://delivery`. */
    private fun parseDeepLinks(block: String): List<String> {
        val out = mutableListOf<String>()
        for (data in DATA_ELEMENT.findAll(block)) {
            val text = data.value
            val scheme = attr(text, "android:scheme") ?: continue
            val host = attr(text, "android:host")
            val path = attr(text, "android:path") ?: attr(text, "android:pathPrefix")
                ?: attr(text, "android:pathPattern")
            out += buildString {
                append(scheme).append("://")
                host?.let { append(it) }
                path?.let { append(it) }
            }
        }
        return out.distinct()
    }

    /**
     * The last line of this component's element.
     *
     * The naive version — "the first line containing `/>`" — ends the block at the first self-closing
     * *child*, which for an activity is its first `<action … />`. Everything after that, including the
     * `<data>` element carrying the deep link, was silently invisible. So the opening tag is closed
     * first, and only then do we look for the matching end tag.
     */
    private fun elementEnd(lines: List<String>, start: Int, kind: ManifestComponent.Kind): Int {
        val tag = kind.name.lowercase()
        // Where the opening tag itself ends.
        var openEnd = start
        while (openEnd < minOf(start + 30, lines.size) && !lines[openEnd].contains('>')) openEnd++
        if (openEnd >= lines.size) return start

        // `<activity … />` — self-closed, no children.
        val openText = lines.subList(start, openEnd + 1).joinToString("\n")
        if (openText.substringBefore('>').trimEnd().endsWith("/") || openText.contains("/>")) return openEnd

        // Otherwise scan for the matching close tag.
        for (i in openEnd until minOf(start + 120, lines.size)) {
            if (lines[i].contains("</$tag>")) return i
        }
        return openEnd
    }

    private fun attr(text: String, name: String): String? =
        Regex("""\b${Regex.escape(name)}\s*=\s*"([^"]*)"""").find(text)?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() }

    private val USES_PERMISSION = Regex("""<uses-permission[^>]*android:name\s*=\s*"([^"]+)"""")
    private val QUERY_PACKAGE = Regex("""<package[^>]*android:name\s*=\s*"([^"]+)"""")
    private val ACTION = Regex("""<action[^>]*android:name\s*=\s*"([^"]+)"""")
    private val DATA_ELEMENT = Regex("""<data\b[^>]*/?>""")

    // ---- the audit ----

    /**
     * [codePermissions] are the permissions the *source* requests at runtime, so the audit can compare
     * what the code asks for against what the manifest declares. That cross-check is the one thing here
     * a manifest alone cannot tell you, and it catches a real and common bug in both directions.
     */
    fun audit(model: ManifestModel, codePermissions: Set<String> = emptySet()): List<AuditFinding> {
        val out = mutableListOf<AuditFinding>()

        for (c in model.components) {
            if (c.isOpenlyExported) {
                val severity = when {
                    // An exported receiver or service taking a custom action is the classic open door:
                    // any app on the device can trigger it.
                    c.kind != ManifestComponent.Kind.ACTIVITY && c.intentActions.any { !it.startsWith("android.") } ->
                        AuditSeverity.HIGH
                    c.kind == ManifestComponent.Kind.PROVIDER -> AuditSeverity.HIGH
                    // An exported launcher activity is how an app starts. Not a finding.
                    c.intentActions.contains("android.intent.action.MAIN") -> continue
                    else -> AuditSeverity.MEDIUM
                }
                out += AuditFinding(
                    severity = severity,
                    title = "${c.kind.label} ${c.name.substringAfterLast('.')} is exported without a permission",
                    detail = "Any app on the device can reach it" +
                        (c.intentActions.firstOrNull()?.let { ", via $it" } ?: "") + ".",
                    component = c.name,
                    line = c.line,
                    remedy = "Set android:exported=\"false\" if nothing outside the app needs it, or " +
                        "guard it with android:permission.",
                )
            }
            // Android 12 made android:exported mandatory for any component with an intent filter; a
            // missing value there is a build failure on modern targets, not a style question.
            if (c.exported == null && c.intentActions.isNotEmpty()) {
                out += AuditFinding(
                    AuditSeverity.MEDIUM,
                    "${c.kind.label} ${c.name.substringAfterLast('.')} has an intent filter but no android:exported",
                    "Since Android 12 this is required whenever a component declares an intent filter.",
                    c.name,
                    c.line,
                    "Add android:exported explicitly — true or false, whichever is intended.",
                )
            }
            if (c.kind == ManifestComponent.Kind.SERVICE && c.foregroundServiceType == null &&
                model.permissions.any { it.startsWith("android.permission.FOREGROUND_SERVICE_") }
            ) {
                out += AuditFinding(
                    AuditSeverity.MEDIUM,
                    "Service ${c.name.substringAfterLast('.')} declares no foregroundServiceType",
                    "The app holds a typed FOREGROUND_SERVICE permission, and API 34 requires the type " +
                        "on the service too.",
                    c.name,
                    c.line,
                    "Add android:foregroundServiceType matching the permission you hold.",
                )
            }
        }

        // Notifications: declaring the permission without ever requesting it means notifications simply
        // never appear on Android 13+, which is a silent failure rather than a crash.
        val postNotifications = "android.permission.POST_NOTIFICATIONS"
        if (postNotifications in model.permissions && postNotifications !in codePermissions &&
            codePermissions.isNotEmpty()
        ) {
            out += AuditFinding(
                AuditSeverity.MEDIUM,
                "POST_NOTIFICATIONS is declared but never requested at runtime",
                "On Android 13+ the permission must be requested; without that, notifications are " +
                    "silently dropped rather than failing visibly.",
                remedy = "Request it with the permission launcher, and handle the denial path.",
            )
        }
        // The other direction: code asks for a permission the manifest doesn't declare.
        for (p in codePermissions - model.permissions.toSet()) {
            out += AuditFinding(
                AuditSeverity.HIGH,
                "${p.substringAfterLast('.')} is requested in code but not declared in the manifest",
                "The request will be denied immediately, without showing a dialog.",
                remedy = "Add <uses-permission android:name=\"$p\" /> to the manifest.",
            )
        }

        if (model.usesCleartextTraffic == true && model.networkSecurityConfig == null) {
            out += AuditFinding(
                AuditSeverity.HIGH,
                "Cleartext traffic is enabled for the whole app",
                "usesCleartextTraffic=\"true\" with no network security config permits plain HTTP " +
                    "to any host.",
                remedy = "Remove it, or scope it to the hosts that need it with a networkSecurityConfig.",
            )
        }
        if (model.hasApplication && model.allowBackup != false &&
            model.dataExtractionRules == null && model.fullBackupContent == null
        ) {
            out += AuditFinding(
                AuditSeverity.MEDIUM,
                "Backup is enabled with no extraction rules",
                "Everything in the app's data directory — including tokens and databases — is eligible " +
                    "for cloud and device-to-device backup.",
                remedy = "Set android:dataExtractionRules (and fullBackupContent for older devices) to " +
                    "exclude credentials, or set android:allowBackup=\"false\".",
            )
        }
        return out.sortedBy { it.severity.ordinal }
    }

    /** `Manifest audit · 2 findings` plus the counts, for a card header. */
    fun summarise(findings: List<AuditFinding>): String {
        if (findings.isEmpty()) return "Manifest audit · nothing found"
        val bySeverity = AuditSeverity.entries.mapNotNull { s ->
            findings.count { it.severity == s }.takeIf { it > 0 }?.let { "$it ${s.name.lowercase()}" }
        }
        return "Manifest audit · ${findings.size} finding${if (findings.size == 1) "" else "s"} " +
            "(${bySeverity.joinToString(", ")})"
    }
}
