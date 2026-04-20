package org.jetbrains.bio.qfarm

import com.github.ajalt.clikt.core.subcommands

fun main(args: Array<String>) =
    RootCommand()
        .subcommands(
            SearchCommand(),
            ValidateCommand()
        )
        .main(args)
