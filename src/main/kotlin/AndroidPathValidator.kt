object AndroidPathValidator {
    private val allowedPrefixes = listOf("/sdcard/", "/storage/emulated/0/")
    // `$`, `'`, and `&` are accepted because the Mods transport
    // single-quotes every remote path (`shellQuoteRemotePath`), which
    // neutralizes device-shell expansion (`'\''` idiom for quotes).
    // Legitimate Unity asset names use them (for example
    // $black_color-....bundle, mango'sm16....bundle,
    // s&wshieldplus-....bundle).  `; | ` < >` stay rejected as defense in
    // depth, and CR/LF stay rejected because `ls`-based inventory parsing
    // splits on newlines.
    private val unsafeCharacters = Regex("""[;|`><\r\n\u0000]""")
    // Unity/Marrow bundle names commonly contain parentheses (for example
    // bl_plane(night).bundle), apostrophes (mango'sm16...bundle), dollar
    // signs, ampersands (s&wshieldplus...bundle), plus signs
    // (reticle++...bundle), equals signs (color=red...bundle), ASCII
    // quotes, and Unicode smart quotes (sr2m"veresk".bundle). They are
    // ordinary path characters, not shell syntax: the Mods transport
    // single-quotes every remote path (`'\''` idiom for quotes), and
    // Windows materializes every one of these except ASCII quotes (which
    // stage through temp names instead).  Retain the existing
    // control/separator checks while accepting them in verified
    // destination paths.
    private val allowedCharacters = Regex("^[A-Za-z0-9._/ ()$'&+=\"\\u201c\\u201d-]+$")

    fun isSafe(path: String): Boolean {
        val value = path.trim()
        if (value.length !in 2..300) return false
        if (value.contains('\\') || value.contains("//")) return false
        if (unsafeCharacters.containsMatchIn(value)) return false
        if (!allowedCharacters.matches(value)) return false
        if (value.split('/').any { it == ".." || it == "." }) return false
        return allowedPrefixes.any(value::startsWith)
    }
}