package com.scanlite.app

enum class Quality(
    val jpegQuality: Int,
    val maxDimension: Int
) {
    SMALL(
        jpegQuality = 60,
        maxDimension = 1600
    ),

    BALANCED(
        jpegQuality = 78,
        maxDimension = 2200
    ),

    HIGH(
        jpegQuality = 92,
        maxDimension = 3000
    )
}

object FileNaming {

    fun pdf(
        prefix: String,
        timestamp: String
    ): String {
        return "$prefix-$timestamp.pdf"
    }
}
