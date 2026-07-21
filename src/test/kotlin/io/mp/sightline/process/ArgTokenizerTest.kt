package io.mp.sightline.process

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `extraArgs` is appended verbatim to every `claude` invocation, so a mis-split here reaches the CLI as
 * arguments the user never typed — and fails in a way that points at the CLI rather than at the field
 * that mangled them.
 */
class ArgTokenizerTest {

    @Test
    fun `blank input produces no arguments`() {
        assertEquals(emptyList<String>(), ArgTokenizer.tokenize(""))
        assertEquals(emptyList<String>(), ArgTokenizer.tokenize("   \t \n "))
    }

    @Test
    fun `plain arguments split on whitespace`() {
        assertEquals(listOf("--verbose", "--foo", "bar"), ArgTokenizer.tokenize("--verbose --foo bar"))
    }

    @Test
    fun `runs of whitespace collapse and surrounding whitespace is ignored`() {
        assertEquals(listOf("a", "b"), ArgTokenizer.tokenize("   a \t\t  b   "))
    }

    /** The case the old whitespace split got wrong. */
    @Test
    fun `a double-quoted value with spaces stays one argument`() {
        assertEquals(
            listOf("--append-system-prompt", "Be concise and cite files"),
            ArgTokenizer.tokenize("""--append-system-prompt "Be concise and cite files""""),
        )
    }

    @Test
    fun `a single-quoted value with spaces stays one argument`() {
        assertEquals(
            listOf("--add-dir", "/Users/x/Application Support/thing"),
            ArgTokenizer.tokenize("--add-dir '/Users/x/Application Support/thing'"),
        )
    }

    @Test
    fun `quotes can open mid-argument`() {
        assertEquals(listOf("--dir=/a b/c"), ArgTokenizer.tokenize("""--dir="/a b/c""""))
    }

    @Test
    fun `an empty quoted string is a real empty argument`() {
        assertEquals(listOf("--flag", ""), ArgTokenizer.tokenize("""--flag """""))
    }

    @Test
    fun `single quotes are literal inside double quotes and vice versa`() {
        assertEquals(listOf("it's fine"), ArgTokenizer.tokenize(""""it's fine""""))
        assertEquals(listOf("""say "hi""""), ArgTokenizer.tokenize("""'say "hi"'"""))
    }

    @Test
    fun `escaped quotes and backslashes are unescaped inside double quotes`() {
        assertEquals(listOf("""a "b" c"""), ArgTokenizer.tokenize("""    "a \"b\" c"    """))
        assertEquals(listOf("""a\b"""), ArgTokenizer.tokenize(""""a\\b""""))
    }

    /** A backslash is a path separator on Windows and part of a regex everywhere else. */
    @Test
    fun `a backslash outside double quotes is literal`() {
        assertEquals(listOf("""C:\Users\me"""), ArgTokenizer.tokenize("""C:\Users\me"""))
        assertEquals(listOf("""\d+"""), ArgTokenizer.tokenize("""'\d+'"""))
    }

    @Test
    fun `a backslash before an ordinary character stays literal inside double quotes`() {
        assertEquals(listOf("""a\nb"""), ArgTokenizer.tokenize(""""a\nb""""))
    }

    /** A half-typed setting should pass what was meant, not vanish. */
    @Test
    fun `an unterminated quote closes at end of input rather than dropping the argument`() {
        assertEquals(listOf("--flag", "half typed"), ArgTokenizer.tokenize("""--flag "half typed"""))
    }

    @Test
    fun `no shell expansion is performed`() {
        assertEquals(listOf("\$HOME", "*.kt", "`whoami`"), ArgTokenizer.tokenize("""${'$'}HOME *.kt `whoami`"""))
    }

    @Test
    fun `adjacent quoted and unquoted parts join into one argument`() {
        assertEquals(listOf("--model=claude opus"), ArgTokenizer.tokenize("""--model="claude opus""""))
    }
}
