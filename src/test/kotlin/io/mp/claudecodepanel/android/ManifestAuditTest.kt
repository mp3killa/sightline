package io.mp.claudecodepanel.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestAuditTest {

    private val manifest = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
            package="com.example.driver.staging">

            <uses-permission android:name="android.permission.INTERNET" />
            <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
            <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
            <uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />

            <application
                android:allowBackup="true"
                android:usesCleartextTraffic="true"
                android:label="Demo">

                <activity
                    android:name="com.example.driver.MainActivity"
                    android:exported="true">
                    <intent-filter>
                        <action android:name="android.intent.action.MAIN" />
                        <category android:name="android.intent.category.LAUNCHER" />
                    </intent-filter>
                    <intent-filter>
                        <action android:name="android.intent.action.VIEW" />
                        <data android:scheme="demo" android:host="delivery" />
                    </intent-filter>
                </activity>

                <receiver
                    android:name="com.example.driver.RouteReceiver"
                    android:exported="true">
                    <intent-filter>
                        <action android:name="com.example.driver.ROUTE_UPDATED" />
                    </intent-filter>
                </receiver>

                <service
                    android:name="com.example.driver.TrackingService"
                    android:exported="false" />
            </application>
        </manifest>
    """.trimIndent()

    private val model = ManifestAudit.parse(manifest)

    // ---- parsing ----

    @Test
    fun `package, permissions and components parse`() {
        assertEquals("com.example.driver.staging", model.packageName)
        assertEquals(4, model.permissions.size)
        assertTrue(model.permissions.contains("android.permission.ACCESS_FINE_LOCATION"))
        assertEquals(3, model.components.size)
    }

    @Test
    fun `component attributes and lines parse`() {
        val receiver = model.components.first { it.kind == ManifestComponent.Kind.RECEIVER }
        assertEquals("com.example.driver.RouteReceiver", receiver.name)
        assertEquals(true, receiver.exported)
        assertTrue(receiver.intentActions.contains("com.example.driver.ROUTE_UPDATED"))
        assertTrue("a finding must be openable", receiver.line > 0)
    }

    @Test
    fun `deep links are reassembled from their data element`() {
        val activity = model.components.first { it.kind == ManifestComponent.Kind.ACTIVITY }
        assertTrue(activity.deepLinks.contains("demo://delivery"))
    }

    @Test
    fun `application flags parse`() {
        assertEquals(true, model.usesCleartextTraffic)
        assertEquals(true, model.allowBackup)
    }

    // ---- the audit ----

    /** An exported receiver with a custom action is the classic open door. */
    @Test
    fun `an exported receiver with a custom action is HIGH`() {
        val f = ManifestAudit.audit(model).first { it.component?.endsWith("RouteReceiver") == true }
        assertEquals(AuditSeverity.HIGH, f.severity)
        assertTrue(f.detail.contains("ROUTE_UPDATED"))
        assertTrue(f.remedy.contains("exported"))
        assertTrue(f.line > 0)
    }

    /** An exported launcher activity is how an app starts, not a finding. */
    @Test
    fun `the launcher activity is not flagged for being exported`() {
        assertTrue(
            ManifestAudit.audit(model).none { it.component?.endsWith("MainActivity") == true },
        )
    }

    /**
     * Scoped to the export finding specifically: this service *is* flagged, for a separate and valid
     * reason (a typed FOREGROUND_SERVICE permission with no matching foregroundServiceType). Asserting
     * "no finding at all" would make the test pass only while that second check didn't exist.
     */
    @Test
    fun `a non-exported service is not flagged for being exported`() {
        assertTrue(
            ManifestAudit.audit(model).none {
                it.component?.endsWith("TrackingService") == true && it.title.contains("exported")
            },
        )
    }

    @Test
    fun `a permission-guarded export is not flagged`() {
        val guarded = ManifestAudit.parse(
            """
            <receiver android:name="com.x.Guarded" android:exported="true"
                android:permission="com.x.PRIVATE">
                <intent-filter><action android:name="com.x.CUSTOM" /></intent-filter>
            </receiver>
            """.trimIndent(),
        )
        assertTrue(ManifestAudit.audit(guarded).none { it.title.contains("exported") })
    }

    /** Android 12 made this mandatory; a missing value is a build failure, not a style question. */
    @Test
    fun `an intent filter with no exported attribute is flagged`() {
        val missing = ManifestAudit.parse(
            """
            <activity android:name="com.x.Legacy">
                <intent-filter><action android:name="android.intent.action.VIEW" /></intent-filter>
            </activity>
            """.trimIndent(),
        )
        assertTrue(ManifestAudit.audit(missing).any { it.title.contains("no android:exported") })
    }

    @Test
    fun `cleartext traffic without a network config is HIGH`() {
        val f = ManifestAudit.audit(model).first { it.title.contains("Cleartext") }
        assertEquals(AuditSeverity.HIGH, f.severity)
        assertTrue(f.remedy.contains("networkSecurityConfig"))
    }

    @Test
    fun `cleartext with a scoped config is accepted`() {
        val scoped = ManifestAudit.parse(
            """<application android:usesCleartextTraffic="true" android:networkSecurityConfig="@xml/net" />""",
        )
        assertTrue(ManifestAudit.audit(scoped).none { it.title.contains("Cleartext") })
    }

    @Test
    fun `backup with no extraction rules is flagged`() {
        val f = ManifestAudit.audit(model).first { it.title.contains("Backup") }
        assertEquals(AuditSeverity.MEDIUM, f.severity)
        assertTrue(f.detail.contains("tokens"))
    }

    @Test
    fun `backup disabled or scoped is accepted`() {
        assertTrue(
            ManifestAudit.audit(ManifestAudit.parse("""<application android:allowBackup="false" />"""))
                .none { it.title.contains("Backup") },
        )
        assertTrue(
            ManifestAudit.audit(
                ManifestAudit.parse("""<application android:dataExtractionRules="@xml/rules" />"""),
            ).none { it.title.contains("Backup") },
        )
    }

    // ---- the code-vs-manifest cross-check ----

    /** Requesting a permission the manifest doesn't declare is denied instantly, with no dialog. */
    @Test
    fun `a permission requested in code but not declared is HIGH`() {
        val findings = ManifestAudit.audit(model, codePermissions = setOf("android.permission.CAMERA"))
        val f = findings.first { it.title.contains("CAMERA") }
        assertEquals(AuditSeverity.HIGH, f.severity)
        assertTrue(f.remedy.contains("uses-permission"))
    }

    /** Declared but never requested means notifications silently never appear on Android 13+. */
    @Test
    fun `POST_NOTIFICATIONS declared but never requested is flagged`() {
        val findings = ManifestAudit.audit(model, codePermissions = setOf("android.permission.INTERNET"))
        assertTrue(findings.any { it.title.contains("POST_NOTIFICATIONS") })
    }

    @Test
    fun `a properly requested notification permission is not flagged`() {
        val findings = ManifestAudit.audit(
            model,
            codePermissions = setOf("android.permission.POST_NOTIFICATIONS"),
        )
        assertTrue(findings.none { it.title.contains("POST_NOTIFICATIONS is declared") })
    }

    /** With no code scan we cannot know, so we must not assert either direction. */
    @Test
    fun `with no code permissions supplied the cross-check is silent`() {
        assertTrue(ManifestAudit.audit(model).none { it.title.contains("POST_NOTIFICATIONS") })
    }

    @Test
    fun `a typed foreground service permission expects a typed service`() {
        assertTrue(
            ManifestAudit.audit(model).any { it.title.contains("foregroundServiceType") },
        )
    }

    // ---- reporting ----

    @Test
    fun `findings are ordered worst first and every one carries a remedy`() {
        val findings = ManifestAudit.audit(model, setOf("android.permission.CAMERA"))
        assertEquals(AuditSeverity.HIGH, findings.first().severity)
        assertTrue("a finding with no action is a complaint", findings.all { it.remedy.isNotBlank() })
    }

    @Test
    fun `the summary counts by severity`() {
        val summary = ManifestAudit.summarise(ManifestAudit.audit(model))
        assertTrue(summary.contains("findings"))
        assertTrue(summary.contains("high"))
    }

    @Test
    fun `a clean manifest reports nothing found`() {
        val clean = ManifestAudit.parse(
            """
            <manifest package="com.x">
                <application android:allowBackup="false" android:dataExtractionRules="@xml/r">
                    <activity android:name="com.x.Main" android:exported="false" />
                </application>
            </manifest>
            """.trimIndent(),
        )
        val findings = ManifestAudit.audit(clean)
        assertTrue("expected no findings, got $findings", findings.isEmpty())
        assertEquals("Manifest audit · nothing found", ManifestAudit.summarise(findings))
    }

    @Test
    fun `an empty manifest does not throw`() {
        assertTrue(ManifestAudit.audit(ManifestAudit.parse("")).isEmpty())
    }

    /** Unreadable attributes must land as unknown, never as false. */
    @Test
    fun `an unparseable exported value is unknown rather than false`() {
        val weird = ManifestAudit.parse(
            """<receiver android:name="com.x.R" android:exported="@bool/isExported" />""",
        )
        assertFalse(weird.components.single().isOpenlyExported)
        assertEquals(null, weird.components.single().exported)
    }
}
