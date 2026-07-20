package io.mp.claudecodepanel.ui.markdown

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.project.Project

/**
 * Highlights fenced code by reusing the IDE's own lexer and colour scheme, so a Kotlin block in the chat
 * is coloured exactly like the editor next to it — in whatever theme the user has, with no palette of our
 * own to maintain.
 *
 * Read-only and best-effort: the block is lexed, never parsed or resolved, so nothing here can touch the
 * project model. Any failure (unknown language, no registered highlighter, a lexer that rejects a partial
 * snippet) yields no spans and the block renders as plain monospace — chat code is often a fragment, and a
 * fragment that won't lex must still be readable.
 */
object CodeHighlighting {

    /** A lexed token range and the attributes the active scheme gives it. */
    data class Span(val start: Int, val end: Int, val attributes: TextAttributes)

    fun spans(project: Project?, language: String?, code: String): List<Span> {
        if (code.isEmpty()) return emptyList()
        val extension = CodeLanguages.extensionFor(language) ?: return emptyList()
        return runCatching { lex(project, extension, code) }.getOrDefault(emptyList())
    }

    private fun lex(project: Project?, extension: String, code: String): List<Span> {
        val fileType = FileTypeManager.getInstance().getFileTypeByExtension(extension)
        if (fileType is UnknownFileType) return emptyList()
        val highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(fileType, project, null) ?: return emptyList()
        val scheme = EditorColorsManager.getInstance().globalScheme

        val lexer = highlighter.highlightingLexer
        lexer.start(code)
        val spans = ArrayList<Span>()
        while (true) {
            val token = lexer.tokenType ?: break
            // Later keys win, matching how the editor layers a token's highlight keys.
            val merged = highlighter.getTokenHighlights(token)
                .mapNotNull { scheme.getAttributes(it) }
                .reduceOrNull { under, over -> TextAttributes.merge(under, over) }
            if (merged != null && lexer.tokenEnd > lexer.tokenStart) {
                spans.add(Span(lexer.tokenStart, lexer.tokenEnd, merged))
            }
            lexer.advance()
        }
        return spans
    }
}
