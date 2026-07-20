package io.mp.sightline.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodeLanguagesTest {

    @Test fun mapsCommonTags() {
        assertEquals("kt", CodeLanguages.extensionFor("kotlin"))
        assertEquals("java", CodeLanguages.extensionFor("java"))
        assertEquals("py", CodeLanguages.extensionFor("python"))
        assertEquals("sh", CodeLanguages.extensionFor("bash"))
    }

    @Test fun tagsAreCaseAndWhitespaceInsensitive() {
        assertEquals("kt", CodeLanguages.extensionFor("  KOTLIN  "))
        assertEquals("ts", CodeLanguages.extensionFor("TypeScript"))
    }

    @Test fun readsOnlyTheFirstWordOfTheInfoString() {
        // ```kotlin title=Foo.kt  — the extras are not part of the language name.
        assertEquals("kt", CodeLanguages.extensionFor("kotlin title=Foo.kt"))
        assertEquals("js", CodeLanguages.extensionFor("js,twoslash"))
    }

    @Test fun aliasesAgree() {
        assertEquals(CodeLanguages.extensionFor("javascript"), CodeLanguages.extensionFor("js"))
        assertEquals(CodeLanguages.extensionFor("yaml"), CodeLanguages.extensionFor("yml"))
        assertEquals(CodeLanguages.extensionFor("c++"), CodeLanguages.extensionFor("cpp"))
    }

    @Test fun unknownOrAbsentTagsHighlightNothing() {
        // Better plain monospace than a confidently wrong language.
        assertNull(CodeLanguages.extensionFor(null))
        assertNull(CodeLanguages.extensionFor(""))
        assertNull(CodeLanguages.extensionFor("   "))
        assertNull(CodeLanguages.extensionFor("no-such-language"))
    }
}
