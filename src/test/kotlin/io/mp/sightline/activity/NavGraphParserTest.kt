package io.mp.sightline.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavGraphParserTest {

    private val navXml = """
        <?xml version="1.0" encoding="utf-8"?>
        <navigation xmlns:android="http://schemas.android.com/apk/res/android"
            xmlns:app="http://schemas.android.com/apk/res-auto"
            app:startDestination="@id/homeFragment">
            <fragment
                android:id="@+id/homeFragment"
                android:name="com.example.home.HomeFragment"
                android:label="Home">
                <argument android:name="userId" app:argType="string" />
                <action android:id="@+id/toDetail" app:destination="@id/detailActivity" />
            </fragment>
            <activity
                android:id="@+id/detailActivity"
                android:name="com.example.detail.DetailActivity" />
            <dialog
                android:id="@+id/confirmDialog"
                android:name="com.example.ConfirmDialog" />
        </navigation>
    """.trimIndent()

    @Test fun parsesFragmentActivityAndDialogDestinations() {
        val dests = NavGraphParser.parse(navXml)
        assertEquals(3, dests.size)
        assertTrue(dests.any { it.className == "com.example.home.HomeFragment" && it.kind == "fragment" })
        assertTrue(dests.any { it.className == "com.example.detail.DetailActivity" && it.kind == "activity" })
        assertTrue(dests.any { it.className == "com.example.ConfirmDialog" && it.kind == "dialog" })
    }

    @Test fun ignoresNonClassAndroidNameAttributes() {
        // The <argument android:name="userId"> must not be mistaken for a destination class.
        assertFalse(NavGraphParser.parse(navXml).any { it.className == "userId" })
    }

    @Test fun detectsNavGraphRoot() {
        assertTrue(NavGraphParser.isNavGraph(navXml))
        assertFalse(NavGraphParser.isNavGraph("""<LinearLayout android:name="not.a.Nav" />"""))
    }

    @Test fun nonNavXmlYieldsNothing() {
        assertTrue(NavGraphParser.parse("<resources><string name=\"app\">App</string></resources>").isEmpty())
    }

    @Test fun deduplicatesRepeatedDestinations() {
        val xml = "<navigation>" +
            "<fragment android:name=\"com.x.A\"/>" +
            "<fragment android:name=\"com.x.A\"/>" +
            "</navigation>"
        assertEquals(1, NavGraphParser.parse(xml).size)
    }
}
