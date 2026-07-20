package io.mp.sightline.activity

/**
 * Identifies a **file-based Android resource** from its path — `res/layout/activity_main.xml` →
 * `(layout, activity_main)`, `res/drawable-hdpi/ic.png` → `(drawable, ic)`. Platform-free and
 * unit-tested. Only file-per-resource types qualify (layout/drawable/menu/navigation/…); value
 * resources (strings/colors declared *inside* the `res/values/` XML) are not file-named, so they return
 * null. `ProjectStructureEnricher` uses this to find the sources that reference a touched resource.
 */
object AndroidResourceParser {

    data class ResourceRef(val type: String, val name: String)

    private val FILE_TYPES = setOf(
        "layout", "menu", "drawable", "mipmap", "anim", "animator", "color",
        "font", "raw", "xml", "navigation", "transition", "interpolator",
    )

    // .../res/<type>[-<qualifiers>]/<name>.<ext>
    private val PATH = Regex(".*/res/([a-z]+)(?:-[^/]+)?/([^/]+)\\.[^./]+\$")

    fun resourceRef(path: String): ResourceRef? {
        val m = PATH.find(path.replace('\\', '/')) ?: return null
        val type = m.groupValues[1]
        if (type !in FILE_TYPES) return null
        return ResourceRef(type, m.groupValues[2])
    }

    /** The code (`R.layout.activity_main`) and XML (`@layout/activity_main`) forms a source may reference. */
    fun referenceForms(ref: ResourceRef): List<String> =
        listOf("R.${ref.type}.${ref.name}", "@${ref.type}/${ref.name}", "@+${ref.type}/${ref.name}")

    /** True if [text] contains a real reference to [ref] (not merely the bare name somewhere). */
    fun isReferencedIn(text: String, ref: ResourceRef): Boolean =
        referenceForms(ref).any { text.contains(it) }
}
