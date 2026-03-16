package dev.eknath.analyzers.models

enum class Severity {
    HIGH, MEDIUM, LOW;

    fun worstWith(other: Severity): Severity =
        if (this.ordinal <= other.ordinal) this else other
}
