package io.mp.claudecodepanel.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceStructureParserTest {

    @Test fun parsesKotlinPackageAndPrimaryType() {
        val src = """
            package com.example.ui

            import com.example.data.UserRepository
            import androidx.lifecycle.ViewModel

            class LoginViewModel(private val repo: UserRepository) : ViewModel() {
                fun x() {}
            }
        """.trimIndent()
        val p = SourceStructureParser.parse(src, "app/src/main/LoginViewModel.kt")
        assertEquals("com.example.ui", p.packageName)
        assertEquals("LoginViewModel", p.primaryType)
        assertNull(p.testTargetType)
    }

    @Test fun parsesJavaPackageAndType() {
        val src = """
            package com.example;
            import com.example.data.UserRepository;
            public class UserService {
            }
        """.trimIndent()
        val p = SourceStructureParser.parse(src, "src/main/java/com/example/UserService.java")
        assertEquals("com.example", p.packageName)
        assertEquals("UserService", p.primaryType)
    }

    @Test fun detectsTestTarget() {
        assertEquals("LoginViewModel", SourceStructureParser.parse("class LoginViewModelTest {}", "LoginViewModelTest.kt").testTargetType)
        assertEquals("Repo", SourceStructureParser.parse("class RepoTests {}", "RepoTests.kt").testTargetType)
        assertEquals("Parser", SourceStructureParser.parse("class ParserSpec {}", "ParserSpec.kt").testTargetType)
        assertNull(SourceStructureParser.testTargetOf("Test"))       // pure "Test" has no target
        assertNull(SourceStructureParser.testTargetOf("LoginViewModel"))
    }

    @Test fun proseDoesNotProduceTypes() {
        val p = SourceStructureParser.parse("This text mentions package and class casually.", "notes.kt")
        assertNull(p.primaryType)
        assertNull(p.packageName)
    }
}
