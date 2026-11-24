package slopsoft.slopify

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform
