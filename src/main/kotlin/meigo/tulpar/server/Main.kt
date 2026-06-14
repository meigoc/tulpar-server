package meigo.tulpar.server

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import meigo.tulpar.server.cli.CheckCommand
import meigo.tulpar.server.cli.StartCommand
import meigo.tulpar.server.cli.TulparCommand
import meigo.tulpar.server.cli.VersionCommand

fun main(args: Array<String>) {
    TulparCommand()
        .subcommands(
            StartCommand(),
            VersionCommand(),
            CheckCommand(),
        )
        .main(args)
}
