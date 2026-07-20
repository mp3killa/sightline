package io.mp.sightline.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidResourceParserTest {

    @Test fun parsesFileBasedResources() {
        assertEquals(
            AndroidResourceParser.ResourceRef("layout", "activity_main"),
            AndroidResourceParser.resourceRef("/app/src/main/res/layout/activity_main.xml"),
        )
        // Density qualifier on the folder is stripped to the base type.
        assertEquals(
            AndroidResourceParser.ResourceRef("drawable", "ic_launcher"),
            AndroidResourceParser.resourceRef("/app/res/drawable-hdpi/ic_launcher.png"),
        )
        assertEquals(
            AndroidResourceParser.ResourceRef("navigation", "nav_graph"),
            AndroidResourceParser.resourceRef("/app/res/navigation/nav_graph.xml"),
        )
    }

    @Test fun ignoresSourcesAndValueResources() {
        assertNull(AndroidResourceParser.resourceRef("/app/src/main/java/com/example/Foo.kt"))
        // Value resources are declared inside the XML, not file-named.
        assertNull(AndroidResourceParser.resourceRef("/app/res/values/strings.xml"))
        assertNull(AndroidResourceParser.resourceRef("/app/res/values-night/colors.xml"))
    }

    @Test fun detectsRealReferencesOnly() {
        val ref = AndroidResourceParser.ResourceRef("layout", "activity_main")
        assertTrue(AndroidResourceParser.isReferencedIn("setContentView(R.layout.activity_main)", ref))
        assertTrue(AndroidResourceParser.isReferencedIn("<fragment tools:layout=\"@layout/activity_main\" />", ref))
        // A bare identifier that merely equals the name is NOT a reference.
        assertFalse(AndroidResourceParser.isReferencedIn("val activity_main = computeThing()", ref))
    }
}
