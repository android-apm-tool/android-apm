package dev.androidapm.catalog

/** Versioned library summaries. Event callbacks are deliberately not content slots. */
object ContentSemantics {
    const val version = "compose-slots-stdlib-v1"
    private val eager = setOf("kotlin.let", "kotlin.run", "kotlin.with", "kotlin.also", "kotlin.apply",
        "kotlin.repeat", "kotlin.collections.forEach", "kotlin.collections.forEachIndexed", "kotlin.collections.map",
        "kotlin.collections.mapIndexed")
    private val lazyBuilders = setOf("androidx.compose.foundation.lazy.LazyColumn", "androidx.compose.foundation.lazy.LazyRow",
        "androidx.compose.foundation.lazy.grid.LazyVerticalGrid", "androidx.compose.foundation.lazy.grid.LazyHorizontalGrid",
        "androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid",
        "androidx.compose.foundation.lazy.staggeredgrid.LazyHorizontalStaggeredGrid")

    fun follows(name: String, argument: Record): Boolean = argument["composableType"] == true ||
        name in eager && argument["name"] in setOf("block", "action", "transform") ||
        name in lazyBuilders && argument["name"] == "content"

    fun known(name: String): Boolean = name in eager || name.startsWith("androidx.compose.")
}
