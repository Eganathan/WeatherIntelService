package dev.eknath.analyzers.models

data class Insight(
    val category: String,
    val severity: Severity,
    val formatArgs: List<Any> = emptyList(),
    val windowStart: Long? = null,
    val windowEnd: Long? = null
)
