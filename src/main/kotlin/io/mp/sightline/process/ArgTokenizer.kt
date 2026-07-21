package io.mp.sightline.process

/**
 * Splits a user-typed command-line fragment into arguments the way a shell would.
 *
 * The advanced `extraArgs` setting is a single text field appended verbatim to every `claude`
 * invocation, so it routinely contains a value with a space in it — a system prompt, a path under
 * `Application Support`, a settings JSON blob. Splitting on whitespace tears those into pieces and
 * hands the CLI arguments the user never wrote, which then fail in a way that points at the CLI rather
 * than at the setting that mangled them.
 *
 * Deliberately *not* a shell: no variable expansion, no globbing, no command substitution. This value
 * becomes process arguments directly and is never handed to a shell, so interpreting `$FOO` or `` ` ``
 * here would invent a capability the user has no reason to expect from a settings field. Quoting is the
 * only shell behaviour honoured, because quoting is the only one needed to express "this is one
 * argument".
 */
object ArgTokenizer {

    /**
     * Tokenises [raw] into arguments.
     *
     * - Unquoted runs of whitespace separate arguments.
     * - `'single'` quotes are literal throughout.
     * - `"double"` quotes are literal except that `\"` and `\\` are unescaped, matching POSIX shells.
     * - A trailing unterminated quote closes at end-of-input rather than dropping the argument: the
     *   value is a half-typed setting, and losing it silently is worse than passing what was meant.
     */
    fun tokenize(raw: String): List<String> {
        val args = mutableListOf<String>()
        val cur = StringBuilder()
        var started = false
        var quote = ' '
        var i = 0

        while (i < raw.length) {
            val c = raw[i]
            when {
                quote == ' ' && c.isWhitespace() -> {
                    if (started) { args.add(cur.toString()); cur.setLength(0); started = false }
                }
                quote == ' ' && (c == '"' || c == '\'') -> { quote = c; started = true }
                quote == c -> quote = ' '
                // Only a double-quoted context unescapes, and only the two characters a POSIX shell
                // does. Elsewhere a backslash is a literal — it is a path separator on Windows and part
                // of a regex everywhere else.
                quote == '"' && c == '\\' && i + 1 < raw.length && (raw[i + 1] == '"' || raw[i + 1] == '\\') -> {
                    cur.append(raw[i + 1]); i++; started = true
                }
                else -> { cur.append(c); started = true }
            }
            i++
        }
        if (started) args.add(cur.toString())
        return args
    }
}
