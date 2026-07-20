package io.mp.sightline.ide

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.mp.sightline.activity.AgentActivityEvent
import io.mp.sightline.activity.StructuralRelation
import io.mp.sightline.activity.StructuralRelationKind

/**
 * The **Kotlin** counterpart to [ProjectStructureEnricherTest] — the enricher resolves imports and the
 * class hierarchy through UAST, which is language-neutral, so this exercises the same code path against
 * real Kotlin PSI (the Kotlin plugin is on the test classpath; see build.gradle.kts). Previously this was
 * only exercised live in the sandbox.
 */
class ProjectStructureEnricherKotlinTest : BasePlatformTestCase() {

    private fun enrich(vf: VirtualFile): List<AgentActivityEvent> {
        val enricher = ProjectStructureEnricher(project, testRootDisposable)
        return ReadAction.compute<List<AgentActivityEvent>, RuntimeException> { enricher.computeFor(vf) }
    }

    fun testKotlinHierarchyAndImportsResolveToProjectFiles() {
        myFixture.addFileToProject("com/example/Base.kt", "package com.example\nopen class Base")
        myFixture.addFileToProject("com/example/Contract.kt", "package com.example\ninterface Contract")
        myFixture.addFileToProject("com/example/data/UserRepo.kt", "package com.example.data\nclass UserRepo")
        val derived = myFixture.addFileToProject(
            "com/example/Derived.kt",
            "package com.example\nimport com.example.data.UserRepo\nclass Derived : Base(), Contract { val r: UserRepo? = null }",
        )
        val rels = enrich(derived.virtualFile).filterIsInstance<StructuralRelation>()
        assertTrue("extends Base", rels.any { it.relation == StructuralRelationKind.EXTENDS && it.targetLabel == "Base" })
        assertTrue("implements Contract", rels.any { it.relation == StructuralRelationKind.IMPLEMENTS && it.targetLabel == "Contract" })
        assertTrue("imports UserRepo", rels.any { it.relation == StructuralRelationKind.IMPORTS && it.targetLabel == "UserRepo" })
    }

    fun testKotlinTestClassResolvesToProduction() {
        myFixture.addFileToProject("com/example/Calculator.kt", "package com.example\nclass Calculator")
        val test = myFixture.addFileToProject("com/example/CalculatorTest.kt", "package com.example\nclass CalculatorTest")
        val rels = enrich(test.virtualFile).filterIsInstance<StructuralRelation>()
        assertTrue("test → production", rels.any { it.relation == StructuralRelationKind.TESTS && it.targetLabel == "Calculator" })
    }
}
