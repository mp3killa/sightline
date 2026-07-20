package io.mp.sightline.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityClassifierTest {

    private fun cat(path: String) = ActivityClassifier.classify(path).category

    @Test fun viewModelIsStateCluster() {
        val c = ActivityClassifier.classify("app/src/main/java/com/x/home/HomeViewModel.kt")
        assertEquals(ActivityCategory.VIEWMODELS_STATE, c.category)
        assertEquals(ActivityNodeType.VIEW_MODEL, c.nodeType)
    }

    @Test fun repositoryIsDataCluster() {
        assertEquals(ActivityCategory.DATA_REPOSITORIES, cat("app/src/main/java/com/x/data/DriverRepository.kt"))
    }

    @Test fun gradleBuildFiles() {
        assertEquals(ActivityCategory.GRADLE_BUILD, cat("build.gradle.kts"))
        assertEquals(ActivityCategory.GRADLE_BUILD, cat("gradle/libs.versions.toml"))
    }

    @Test fun composableScreenIsUi() {
        assertEquals(ActivityCategory.UI_COMPOSE, cat("app/src/main/java/com/x/ui/LoginScreen.kt"))
    }

    @Test fun testSourcesAreTesting() {
        assertEquals(ActivityCategory.TESTING, cat("app/src/test/java/com/x/HomeViewModelTest.kt"))
    }

    @Test fun docsAndConfig() {
        assertEquals(ActivityCategory.DOCUMENTATION, cat("docs/ARCHITECTURE.md"))
        assertEquals(ActivityCategory.CONFIGURATION, cat("app/src/main/AndroidManifest.xml"))
    }

    @Test fun unknownFallbackHasLowerConfidence() {
        val c = ActivityClassifier.classify("scripts/whatever.bin")
        assertEquals(ActivityCategory.UNKNOWN, c.category)
        assertTrue(c.confidence < 0.6f)
    }

    @Test fun basenameAndNormalize() {
        assertEquals("Foo.kt", ActivityClassifier.basename("/a/b/Foo.kt"))
        assertEquals("a/b/Foo.kt", ActivityClassifier.normalizePath("./a/b/Foo.kt/"))
        assertEquals("a/b", ActivityClassifier.normalizePath("a\\b"))
    }

    @Test fun nullPathIsUnknown() {
        assertEquals(ActivityCategory.UNKNOWN, ActivityClassifier.classify(null).category)
    }
}
