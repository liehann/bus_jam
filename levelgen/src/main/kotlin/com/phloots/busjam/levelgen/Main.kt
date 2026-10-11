package com.phloots.busjam.levelgen

import kotlin.system.exitProcess

/** Placeholder entry point. Owner: generator agent. */
fun main(args: Array<String>) {
    println(
        """
        Usage: ./gradlew :levelgen:run --args="[options]"

          --seed <long>        RNG seed (default 1)
          --count <n>          number of campaign levels (default 1000)
          --out <path>         output levels.json (default app/src/main/assets/levels.json)
          --csv <path>         difficulty curve CSV (default stdout table only)

        Not implemented yet.
        """.trimIndent()
    )
    if (args.isNotEmpty()) exitProcess(2)
}
