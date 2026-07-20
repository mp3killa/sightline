package io.mp.sightline.ide

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.mp.sightline.activity.AgentActivityEvent
import io.mp.sightline.activity.FilePackage
import io.mp.sightline.activity.StructuralRelation
import io.mp.sightline.activity.StructuralRelationKind

/**
 * Verifies the real PSI/UAST resolution in [ProjectStructureEnricher] against a live (in-memory) Java
 * project. This is the platform-fixture test the plain unit tests can't cover; Kotlin uses the same
 * UAST code path at runtime (its provider isn't on the test classpath, so it's exercised in the sandbox).
 */
class ProjectStructureEnricherTest : BasePlatformTestCase() {

    private fun enrich(vf: VirtualFile): List<AgentActivityEvent> {
        val enricher = ProjectStructureEnricher(project, testRootDisposable)
        return ReadAction.compute<List<AgentActivityEvent>, RuntimeException> { enricher.computeFor(vf) }
    }

    fun testExtendsAndImplementsResolveToProjectFiles() {
        myFixture.addFileToProject("com/example/Base.java", "package com.example; public class Base {}")
        myFixture.addFileToProject("com/example/Contract.java", "package com.example; public interface Contract {}")
        val derived = myFixture.addFileToProject(
            "com/example/Derived.java",
            "package com.example; public class Derived extends Base implements Contract {}",
        )
        val rels = enrich(derived.virtualFile).filterIsInstance<StructuralRelation>()
        assertTrue("extends Base", rels.any { it.relation == StructuralRelationKind.EXTENDS && it.targetLabel == "Base" })
        assertTrue("implements Contract", rels.any { it.relation == StructuralRelationKind.IMPLEMENTS && it.targetLabel == "Contract" })
    }

    fun testImportResolvesToProjectFileAndPackageMetadata() {
        myFixture.addFileToProject("com/example/data/UserRepo.java", "package com.example.data; public class UserRepo {}")
        val svc = myFixture.addFileToProject(
            "com/example/UserService.java",
            "package com.example; import com.example.data.UserRepo; public class UserService { UserRepo r; }",
        )
        val events = enrich(svc.virtualFile)
        assertTrue(
            "imports UserRepo",
            events.filterIsInstance<StructuralRelation>().any {
                it.relation == StructuralRelationKind.IMPORTS && it.targetLabel == "UserRepo"
            },
        )
        assertTrue(
            "package metadata",
            events.filterIsInstance<FilePackage>().any { it.packageName == "com.example" },
        )
    }

    fun testNavGraphDestinationsResolveToScreenFiles() {
        myFixture.addFileToProject("com/example/HomeFragment.java", "package com.example; public class HomeFragment {}")
        myFixture.addFileToProject("com/example/DetailActivity.java", "package com.example; public class DetailActivity {}")
        val nav = myFixture.addFileToProject(
            "res/navigation/nav_graph.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <navigation xmlns:android="http://schemas.android.com/apk/res/android">
                <fragment android:id="@+id/home" android:name="com.example.HomeFragment" />
                <activity android:id="@+id/detail" android:name="com.example.DetailActivity" />
            </navigation>
            """.trimIndent(),
        )
        val rels = enrich(nav.virtualFile).filterIsInstance<StructuralRelation>()
        assertTrue("navigates to HomeFragment", rels.any {
            it.relation == StructuralRelationKind.NAVIGATES_TO && it.targetLabel == "HomeFragment"
        })
        assertTrue("navigates to DetailActivity", rels.any {
            it.relation == StructuralRelationKind.NAVIGATES_TO && it.targetLabel == "DetailActivity"
        })
    }

    fun testFindUsagesReturnsReferencingFiles() {
        val svc = myFixture.addFileToProject("com/example/UserService.java", "package com.example; public class UserService {}")
        myFixture.addFileToProject("com/example/Caller.java", "package com.example; public class Caller { UserService s; }")
        val enricher = ProjectStructureEnricher(project, testRootDisposable)
        val rels = ReadAction.compute<List<AgentActivityEvent>, RuntimeException> { enricher.findUsages(svc.virtualFile) }
            .filterIsInstance<StructuralRelation>()
        assertTrue(
            "UserService used by Caller",
            rels.any { it.relation == StructuralRelationKind.REFERENCED_BY && it.targetLabel == "Caller" },
        )
    }

    fun testResourceReverseLookupFindsReferencingSource() {
        myFixture.addFileToProject(
            "com/example/MainActivity.java",
            "package com.example; public class MainActivity { void go() { setContentView(R.layout.activity_main); } void setContentView(int i) {} }",
        )
        val layout = myFixture.addFileToProject("res/layout/activity_main.xml", "<LinearLayout/>")
        val rels = enrich(layout.virtualFile).filterIsInstance<StructuralRelation>()
        assertTrue(
            "resource referenced by MainActivity",
            rels.any { it.relation == StructuralRelationKind.REFERENCED_BY && it.targetLabel == "MainActivity" },
        )
    }

    fun testLibraryOrImplicitSupertypesAreNotLinked() {
        // A plain class (implicit java.lang.Object superclass) must not produce a hierarchy edge.
        val plain = myFixture.addFileToProject("com/example/Plain.java", "package com.example; public class Plain {}")
        val rels = enrich(plain.virtualFile).filterIsInstance<StructuralRelation>()
        assertTrue("no supertype edges for a plain class", rels.none {
            it.relation == StructuralRelationKind.EXTENDS || it.relation == StructuralRelationKind.IMPLEMENTS
        })
    }
}
