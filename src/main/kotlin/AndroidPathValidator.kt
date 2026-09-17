object AndroidPathValidator {
    private val allowedPrefixes = listOf("/sdcard/", "/storage/emulated/0/")
    private val unsafeCharacters = Regex("""[;&|`$><\r\n\u0000]""")
    // Unity/Marrow bundle names commonly contain parentheses (for example
    // bl_plane(night).bundle). They are ordinary path characters, not shell
    // syntax; retain the existing control/separator checks while accepting
    // them in verified destination paths.
    private val allowedCharacters = Regex("""^[A-Za-z0-9._/ ()-]+$""")

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