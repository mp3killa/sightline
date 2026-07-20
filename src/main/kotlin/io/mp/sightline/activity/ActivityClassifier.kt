package io.mp.sightline.activity

/**
 * Cheap, PSI-free classification of a file path into an [ActivityCategory] and a refined
 * [ActivityNodeType], using filename/path heuristics. Pure and deterministic so it can be unit
 * tested and cached. Rules are checked most-specific first; when nothing matches we fall back to
 * [ActivityCategory.UNKNOWN] with low confidence.
 *
 * This is intentionally extensible: the ordered [rules] list can be replaced/augmented for
 * non-Android projects without touching the reducer.
 */
object ActivityClassifier {

    /** Result of classifying a path. */
    data class Classification(
        val category: ActivityCategory,
        val nodeType: ActivityNodeType,
        val confidence: Float,
    )

    private data class Rule(
        val category: ActivityCategory,
        val nodeType: ActivityNodeType,
        val confidence: Float,
        val match: (name: String, lower: String) -> Boolean,
    )

    // name = basename, lower = full path lower-cased. Order matters (first match wins).
    private val rules: List<Rule> = listOf(
        Rule(ActivityCategory.TESTING, ActivityNodeType.TEST, 0.95f) { name, lower ->
            lower.contains("/test/") || lower.contains("/androidtest/") || lower.contains("/src/test") ||
                name.endsWith("Test.kt") || name.endsWith("Test.java") ||
                name.endsWith("Tests.kt") || name.endsWith("Spec.kt") || name.endsWith("Test.groovy")
        },
        Rule(ActivityCategory.GRADLE_BUILD, ActivityNodeType.GRADLE_TASK, 0.95f) { name, lower ->
            name == "build.gradle" || name == "build.gradle.kts" || name == "settings.gradle" ||
                name == "settings.gradle.kts" || name == "gradle.properties" || name == "libs.versions.toml" ||
                name.endsWith(".pro") || lower.contains("/gradle/wrapper")
        },
        Rule(ActivityCategory.RESOURCES, ActivityNodeType.FILE, 0.85f) { _, lower ->
            lower.contains("/res/") || lower.contains("/resources/") || lower.endsWith(".png") ||
                lower.endsWith(".webp") || lower.endsWith(".svg") || lower.endsWith(".ttf")
        },
        Rule(ActivityCategory.DOCUMENTATION, ActivityNodeType.DOCUMENTATION, 0.9f) { name, lower ->
            lower.endsWith(".md") || lower.endsWith(".adoc") || name == "LICENSE" ||
                name.startsWith("README") || name.startsWith("CHANGELOG") || lower.contains("/docs/")
        },
        Rule(ActivityCategory.CONFIGURATION, ActivityNodeType.FILE, 0.8f) { name, lower ->
            name == "AndroidManifest.xml" || name == "proguard-rules.pro" || lower.endsWith(".yml") ||
                lower.endsWith(".yaml") || lower.endsWith(".toml") || lower.endsWith(".ini") ||
                lower.endsWith(".cfg") || (lower.endsWith(".json") && !lower.contains("/src/"))
        },
        Rule(ActivityCategory.DEPENDENCY_INJECTION, ActivityNodeType.CLASS, 0.7f) { name, lower ->
            lower.contains("/di/") || lower.contains("/inject") || name.contains("Hilt") ||
                name.contains("Dagger") || name.endsWith("Module.kt") || name.endsWith("Component.kt")
        },
        Rule(ActivityCategory.NAVIGATION, ActivityNodeType.CLASS, 0.7f) { name, lower ->
            lower.contains("/navigation/") || name.contains("NavGraph") || name.contains("NavHost") ||
                name.startsWith("Nav") || name.endsWith("Navigation.kt") || name.endsWith("Destinations.kt")
        },
        Rule(ActivityCategory.VIEWMODELS_STATE, ActivityNodeType.VIEW_MODEL, 0.9f) { name, _ ->
            name.endsWith("ViewModel.kt") || name.endsWith("ViewModel.java") ||
                name.endsWith("State.kt") || name.endsWith("UiState.kt") || name.endsWith("Store.kt")
        },
        Rule(ActivityCategory.DATA_REPOSITORIES, ActivityNodeType.REPOSITORY, 0.85f) { name, lower ->
            name.contains("Repository") || lower.contains("/repository/") ||
                (lower.contains("/data/") && !lower.contains("/database"))
        },
        Rule(ActivityCategory.DOMAIN_USECASES, ActivityNodeType.USE_CASE, 0.8f) { name, lower ->
            name.contains("UseCase") || name.contains("Interactor") ||
                lower.contains("/usecase") || lower.contains("/domain/")
        },
        Rule(ActivityCategory.PERSISTENCE_DB, ActivityNodeType.CLASS, 0.75f) { name, lower ->
            name.endsWith("Dao.kt") || name.endsWith("Entity.kt") || name.contains("Database") ||
                lower.contains("/db/") || lower.contains("/database/") || lower.contains("/local/") ||
                name.contains("Room")
        },
        Rule(ActivityCategory.NETWORKING_APIS, ActivityNodeType.API_ENDPOINT, 0.7f) { name, lower ->
            name.endsWith("Api.kt") || name.endsWith("ApiService.kt") || name.endsWith("Client.kt") ||
                name.endsWith("Dto.kt") || name.contains("Retrofit") || name.contains("Ktor") ||
                lower.contains("/network/") || lower.contains("/remote/") || lower.contains("/api/")
        },
        Rule(ActivityCategory.UI_COMPOSE, ActivityNodeType.COMPOSABLE, 0.7f) { name, lower ->
            name.endsWith("Screen.kt") || name.endsWith("Composable.kt") || name.contains("Compose") ||
                name.endsWith("Theme.kt") || name.endsWith("Color.kt") || name.endsWith("Widget.kt") ||
                lower.contains("/ui/") || lower.contains("/compose/") || lower.contains("/presentation/")
        },
        Rule(ActivityCategory.ANDROID_FRAMEWORK, ActivityNodeType.CLASS, 0.7f) { name, _ ->
            name.endsWith("Activity.kt") || name.endsWith("Fragment.kt") || name.endsWith("Application.kt") ||
                name.endsWith("Receiver.kt") || name.endsWith("Service.kt")
        },
        Rule(ActivityCategory.UTILITIES, ActivityNodeType.FILE, 0.6f) { name, lower ->
            name.contains("Util") || name.endsWith("Ext.kt") || name.endsWith("Extensions.kt") ||
                name.contains("Helper") || lower.contains("/util") || lower.contains("/common/")
        },
    )

    fun classify(path: String?): Classification {
        if (path.isNullOrBlank()) return Classification(ActivityCategory.UNKNOWN, ActivityNodeType.FILE, 0.4f)
        val name = basename(path)
        val lower = path.replace('\\', '/').lowercase()
        for (r in rules) {
            if (r.match(name, lower)) return Classification(r.category, r.nodeType, r.confidence)
        }
        // A generic source file with no signal still deserves a home.
        val nodeType = if (lower.endsWith(".kt") || lower.endsWith(".java")) ActivityNodeType.FILE
        else ActivityNodeType.FILE
        return Classification(ActivityCategory.UNKNOWN, nodeType, 0.5f)
    }

    fun basename(path: String): String {
        val p = path.replace('\\', '/').trimEnd('/')
        val slash = p.lastIndexOf('/')
        return if (slash >= 0) p.substring(slash + 1) else p
    }

    /** Normalises a path for use as a node key (forward slashes, no trailing slash, no leading `./`). */
    fun normalizePath(path: String): String {
        var p = path.replace('\\', '/').trim()
        while (p.startsWith("./")) p = p.substring(2)
        if (p.length > 1) p = p.trimEnd('/')
        return p
    }
}
