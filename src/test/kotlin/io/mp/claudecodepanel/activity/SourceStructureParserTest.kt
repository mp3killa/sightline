package io.mp.claudecodepanel.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceStructureParserTest {

    @Test fun parsesKotlinPackageImportsAndPrimaryType() {
        val src = """
            package com.example.ui

            import com.example.data.UserRepository
            import com.example.data.model.User
            import androidx.lifecycle.ViewModel
            import com.example.util.formatDate   // lowercase function import -> dropped
            import com.example.data.*             // wildcard -> dropped

            class LoginViewModel(private val repo: UserRepository) : ViewModel() {
                fun x(u: User) {}
            }
        """.trimIndent()
        val p = SourceStructureParser.parse(src, "app/src/main/LoginViewModel.kt")
        assertEquals("com.example.ui", p.packageName)
        assertEquals("LoginViewModel", p.primaryType)
        assertTrue(p.importedTypes.containsAll(listOf("UserRepository", "User", "ViewModel")))
        assertFalse(p.importedTypes.contains("formatDate"))
        assertEquals(3, p.importedTypes.size) // no wildcard, no lowercase
        assertNull(p.testTargetType)
    }

    @Test fun parsesJavaImportsAndType() {
        val src = """
            package com.example;
            import com.example.data.UserRepository;
            import static com.example.Util.helper;
            public class UserService {
            }
        """.trimIndent()
        val p = SourceStructureParser.parse(src, "src/main/java/com/example/UserService.java")
        assertEquals("com.example", p.packageName)
        assertEquals("UserService", p.primaryType)
        assertTrue(p.importedTypes.contains("UserRepository"))
        assertFalse(p.importedTypes.contains("helper")) // static lowercase member import
    }

    @Test fun detectsTestTarget() {
        assertEquals("LoginViewModel", SourceStructureParser.parse("class LoginViewModelTest {}", "LoginViewModelTest.kt").testTargetType)
        assertEquals("Repo", SourceStructureParser.parse("class RepoTests {}", "RepoTests.kt").testTargetType)
        assertEquals("Parser", SourceStructureParser.parse("class ParserSpec {}", "ParserSpec.kt").testTargetType)
        assertNull(SourceStructureParser.testTargetOf("Test"))       // pure "Test" has no target
        assertNull(SourceStructureParser.testTargetOf("LoginViewModel"))
    }

    @Test fun proseDoesNotProduceImportsOrTypes() {
        val p = SourceStructureParser.parse("This text mentions import and class casually.", "notes.kt")
        assertTrue(p.importedTypes.isEmpty())
        assertNull(p.primaryType)
        assertNull(p.packageName)
    }
}
