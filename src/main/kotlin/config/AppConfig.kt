package dev.eknath.config

object AppConfig {
    val owmApiKey: String = System.getenv("OWM_API_KEY")
        ?: error("OWM_API_KEY environment variable is required")

    val apiKey: String = System.getenv("WI_SERVICE_KEY")
        ?: error("WI_SERVICE_KEY environment variable is required")

    val port: Int = System.getenv("PORT")?.toIntOrNull() ?: 8080
}
