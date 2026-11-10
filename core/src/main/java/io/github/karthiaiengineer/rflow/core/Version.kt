package io.github.karthiaiengineer.rflow.core

/** A release version, "v1.2.3" or "1.2.3". Compared number by number, so 0.10.0 is newer than 0.9.9. */
data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
    override fun compareTo(other: Version): Int =
        compareValuesBy(this, other, Version::major, Version::minor, Version::patch)

    override fun toString() = "$major.$minor.$patch"

    companion object {
        private val PATTERN = Regex("""^v?(\d+)\.(\d+)\.(\d+)$""")

        /** Null for anything that isn't a plain version (a pre-release tag is never offered as an update). */
        fun parse(text: String): Version? =
            PATTERN.matchEntire(text.trim())?.destructured?.let { (a, b, c) -> Version(a.toInt(), b.toInt(), c.toInt()) }
    }
}
