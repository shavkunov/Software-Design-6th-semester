package ru.spbau.mit.aush

import ru.spbau.mit.aush.ast.Environment
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import kotlin.concurrent.thread

sealed class Command {
    abstract fun evaluate(
            args: List<String> = emptyList(),
            environment: Environment
    )

    companion object {
        fun getCommand(name: String): Command = commands[name] ?: ExternalCommand(name)

        private val commands = mapOf(
                "cat" to CatCommand,
                "echo" to EchoCommand,
                "wc" to WcCommand,
                "pwd" to PwdCommand,
                "exit" to ExitCommand
        )
    }
}

private object CatCommand : Command() {
    override fun evaluate(
            args: List<String>,
            environment: Environment
    ) {
        if (args.isEmpty()) {
            environment.io.input.copyTo(environment.io.output)
        } else {
            for (fileName in args) {
                File(fileName).inputStream().copyTo(environment.io.output)
            }
        }
    }
}

private object EchoCommand : Command() {
    override fun evaluate(
            args: List<String>,
            environment: Environment
    ) {
        environment.io.output.writer().run {
            for (string in args) {
                append(string + " ")
            }
            appendln()
        }
    }
}

private object WcCommand : Command() {
    private data class Stats(
            val lines: Int,
            val words: Int,
            val bytes: Int) {
        operator fun plus(other: Stats) =
                Stats(
                        lines + other.lines,
                        words + other.words,
                        bytes + other.bytes
                )

        fun toOutputStream(output: OutputStream, comment: String = "") =
                output
                        .writer()
                        .append("\t$lines")
                        .append("\t$words")
                        .append("\t$bytes")
                        .appendln(if (comment.isEmpty()) "" else "\t$comment")
    }

    private fun InputStream.calculateStats(): Stats =
            reader().readText().let {text ->
                Stats(
                        text.lines().size,
                        text.split(' ', '\n', '\t').size,
                        text.length
                )
            }

    private fun File.calculateStats(): Stats = inputStream().calculateStats()

    override fun evaluate(
            args: List<String>,
            environment: Environment
    ) {
        when (args.size) {
            0 -> environment.io.input
                    .calculateStats()
                    .toOutputStream(environment.io.output)
            1 -> File(args[0])
                    .calculateStats()
                    .toOutputStream(environment.io.output, args[0])
            2 -> {
                var total = Stats(0, 0, 0)

                for (filename in args) {
                    val fileStats = File(filename).calculateStats()
                    fileStats.toOutputStream(environment.io.output, filename)
                    total += fileStats
                }

                total.toOutputStream(environment.io.output, "total")
            }
        }
    }
}

private object PwdCommand : Command() {
    override fun evaluate(
            args: List<String>,
            environment: Environment
    ) {
        PrintStream(environment.io.output).println(System.getProperty("user.dir"))
    }
}

object ExitCommand : Command() {
    override fun evaluate(
            args: List<String>,
            environment: Environment
    ) {

    }
}

private data class ExternalCommand(val name: String) : Command() {
    override fun evaluate(
            args: List<String>,
            environment: Environment
    ) {
        val subProcessBuilder = ProcessBuilder(listOf(name) + args)
        subProcessBuilder.environment()?.putAll(environment.variables.data)

        val subProcess = subProcessBuilder.start()
        val subInput = subProcess.inputStream
        val subOutput = subProcess.outputStream
        val subError = subProcess.errorStream

        val pipeStreamThread = thread(true) {
            val input = environment.io.input.bufferedReader()
            val output = subOutput.bufferedWriter()
            val buffer = CharArray(BUFFER_SIZE)

            while (subProcess.isAlive) {
                val readBytes = input.read(buffer)
                if (readBytes == -1) {
                    break
                } else {
                    output.write(buffer, 0, readBytes)
                }
            }
        }

        subProcess.waitFor()
        pipeStreamThread.join()

        subInput.copyTo(environment.io.output)
        subError.copyTo(environment.io.error)
    }

    private companion object {
        const val BUFFER_SIZE = 512
    }
}