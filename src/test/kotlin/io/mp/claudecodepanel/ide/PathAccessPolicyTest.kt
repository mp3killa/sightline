package io.mp.claudecodepanel.ide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PathAccessPolicyTest {

    private lateinit var project: File
    private lateinit var home: File
    private lateinit var policy: PathAccessPolicy

    @Before fun setUp() {
        project = Files.createTempDirectory("cap-project").toFile()
        home = Files.createTempDirectory("cap-home").toFile()
        File(project, "src/main").mkdirs()
        policy = PathAccessPolicy(listOf(project.path), userHome = home.path)
    }

    private fun inProject(rel: String) = File(project, rel).path
    private fun inHome(rel: String) = File(home, rel).path

    @Test fun fileInsideProjectIsInside() {
        assertEquals(PathAccess.INSIDE_PROJECT, policy.classify(inProject("src/main/App.kt")))
        assertEquals(PathAccess.INSIDE_PROJECT, policy.classify(project.path))
        assertTrue(policy.isInsideProject(inProject("build.gradle.kts")))
    }

    @Test fun fileOutsideProjectIsOutside() {
        val sibling = Files.createTempDirectory("cap-other").toFile()
        assertEquals(PathAccess.OUTSIDE_PROJECT, policy.classify(File(sibling, "notes.txt").path))
    }

    @Test fun dotDotEscapeIsResolvedAndOutside() {
        // project/../<something outside> must not be mistaken for inside via a raw prefix check.
        val escape = inProject("../escape.kt")
        assertEquals(PathAccess.OUTSIDE_PROJECT, policy.classify(escape))
    }

    @Test fun dotDotThatStaysInsideIsInside() {
        assertEquals(PathAccess.INSIDE_PROJECT, policy.classify(inProject("src/main/../main/App.kt")))
    }

    @Test fun envAndIdeAndGitAreSensitiveEvenInsideProject() {
        assertEquals(PathAccess.SENSITIVE, policy.classify(inProject(".env")))
        assertEquals(PathAccess.SENSITIVE, policy.classify(inProject(".env.local")))
        assertEquals(PathAccess.SENSITIVE, policy.classify(inProject(".idea/workspace.xml")))
        assertEquals(PathAccess.SENSITIVE, policy.classify(inProject(".git/config")))
    }

    @Test fun homeCredentialsAreSensitive() {
        assertEquals(PathAccess.SENSITIVE, policy.classify(inHome(".ssh/id_rsa")))
        assertEquals(PathAccess.SENSITIVE, policy.classify(inHome(".aws/credentials")))
        assertEquals(PathAccess.SENSITIVE, policy.classify(inHome(".claude/settings.json")))
    }

    @Test fun homeDotfilesAreSensitive() {
        assertEquals(PathAccess.SENSITIVE, policy.classify(inHome(".zshrc")))
        assertEquals(PathAccess.SENSITIVE, policy.classify(inHome(".gitconfig")))
    }

    @Test fun keyMaterialIsSensitiveAnywhere() {
        assertEquals(PathAccess.SENSITIVE, policy.classify(inProject("keys/id_ed25519")))
        assertEquals(PathAccess.SENSITIVE, policy.classify(inProject(".git-credentials")))
    }

    @Test fun fileUriInsideProjectIsInside() {
        // Java's File.toURI() → file:/path (no authority); IntelliJ's VirtualFile.url → file:///path.
        assertEquals(PathAccess.INSIDE_PROJECT,
            policy.classify(File(project, "src/main/App.kt").toURI().toString()))
        assertEquals(PathAccess.INSIDE_PROJECT,
            policy.classify("file://" + File(project, "src/main/App.kt").path))
    }

    @Test fun blankOrUnresolvableIsSensitive() {
        assertEquals(PathAccess.SENSITIVE, policy.classify(""))
        assertEquals(PathAccess.SENSITIVE, policy.classify("   "))
        assertFalse(policy.isInsideProject(""))
    }

    @Test fun regularHomeFileOutsideProjectIsMerelyOutside() {
        assertEquals(PathAccess.OUTSIDE_PROJECT, policy.classify(inHome("Documents/notes.txt")))
    }
}
