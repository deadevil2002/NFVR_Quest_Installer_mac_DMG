import java.util.Properties

object AppInfo {
    val version: String by lazy {
        val properties = Properties()
        AppInfo::class.java.getResourceAsStream("/app.properties")?.use(properties::load)
        properties.getProperty("version")?.trim().orEmpty().ifBlank { "unknown" }
    }
}