package io.github.szymonbonkowski.myndhamr

import io.github.szymonbonkowski.myndhamr.domain.FoundationVersion

fun main(args: Array<String>) {
    println(foundationCommand(args.toList()))
}

fun foundationCommand(args: List<String>): String = when (args) {
    emptyList<String>() -> "Myndhamr ${FoundationVersion.MILESTONE} foundation"
    listOf("--help") -> "Usage: myndhamr [--help]"
    else -> throw IllegalArgumentException("Invalid arguments: ${args.joinToString(" ")}")
}
