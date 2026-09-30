package io.github.szymonbonkowski.myndhamr

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform