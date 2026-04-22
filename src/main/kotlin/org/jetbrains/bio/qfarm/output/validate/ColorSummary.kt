package org.jetbrains.bio.qfarm.output.validate

object Ansi {
    const val RESET = "\u001B[0m"

    const val RED = "\u001B[31m"
    const val GREEN = "\u001B[32m"

    const val BOLD = "\u001B[1m"

    const val BG_RED = "\u001B[41m"
    const val WHITE = "\u001B[37m"
}

fun highlight(value: String, rowColor: String): String {
    return "${Ansi.BG_RED}${Ansi.WHITE}${Ansi.BOLD}$value${Ansi.RESET}$rowColor"
}
