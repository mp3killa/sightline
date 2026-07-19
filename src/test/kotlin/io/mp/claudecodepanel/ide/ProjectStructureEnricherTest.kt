package io.mp.claudecodepanel.ide

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.mp.claudecodepanel.activity.AgentActivityEvent
import io.mp.claudecodepanel.activity.FilePackage
import io.mp.claudecodepanel.activity.StructuralRelation
import io.mp.claudecodepanel.activity.StructuralRelationKind

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

    fun testLibraryOrImplicitSupertypesAreNotLinked() {
        // A plain class (implicit java.lang.Object superclass) must not produce a hierarchy edge.
        val plain = myFixture.addFileToProject("com/example/Plain.java", "package com.example; public class Plain {}")
        val rels = enrich(plain.virtualFile).filterIsInstance<StructuralRelation>()
        assertTrue("no supertype edges for a plain class", rels.none {
            it.relation == StructuralRelationKind.EXTENDS || it.relation == StructuralRelationKind.IMPLEMENTS
        })
    }
}
