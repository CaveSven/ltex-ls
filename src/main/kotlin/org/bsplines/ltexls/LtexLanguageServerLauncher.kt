/* Copyright (C) 2019-2023 Julian Valentin, LTeX Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls

import org.bsplines.ltexls.client.LtexLanguageClient
import org.bsplines.ltexls.server.LtexLanguageServer
import org.bsplines.ltexls.tools.I18n
import org.bsplines.ltexls.tools.Logging
import org.bsplines.ltexls.tools.TeeInputStream
import org.bsplines.ltexls.tools.TeeOutputStream
import org.bsplines.ltexls.tools.VersionProvider
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageClient
import org.fusesource.jansi.AnsiConsole
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.system.exitProcess

@Command(
  name = "ltex-ls",
  mixinStandardHelpOptions = true,
  showDefaultValues = true,
  versionProvider = VersionProvider::class,
  description = ["LTeX LS - LTeX Language Server"],
)
class LtexLanguageServerLauncher : Callable<Int> {
  @Option(
    names = ["--endless"],
    negatable = true,
    description = ["Keep server alive when client terminates."],
  )
  private var endless: Boolean = false

  @Option(
    names = ["--server-type"],
    description = ["Run the server as type <serverType>. Valid values: \${COMPLETION-CANDIDATES}"],
  )
  private var serverType: ServerType = ServerType.StandardStream

  @Option(
    names = ["--host"],
    description = [
      "Listen for TCP connections on host <host> "
      + "(IP address or hostname; only relevant if server type is tcpSocket).",
    ],
  )
  private var host: String = "localhost"

  @Option(
    names = ["--port"],
    description = [
      "Listen for TCP connections on port <port> "
      + "(only relevant if server type is tcpSocket). "
      + "A value of 0 will have the system automatically determine a free port "
      + "(the actual port number will be printed to the log).",
    ],
  )
  private var port: Int = 0

  @Option(
    names = ["--log-file"],
    description = [
      "Tee server/client communication and server log "
      + "to <logFile>. $${'$'}{PID} is replaced by the process ID of LTeX LS. "
      + "The parent directory of <logFile> must exist. "
      + "If <logFile> is an existing directory, then ltex-ls-$${'$'}{PID}.log is used as filename.",
    ],
  )
  private var logFile: Path? = null

  override fun call(): Int {
    var serverSocket: ServerSocket? = null
    var logOutputStream: OutputStream? = null

    try {
      if (this.logFile != null) logOutputStream = setupLogFileOutput()

      val port: Int = if (this.serverType == ServerType.TcpSocket) {
        serverSocket = ServerSocket(
          this.port,
          SERVER_SOCKET_BACKLOG_SIZE,
          InetAddress.getByName(this.host),
        )
        serverSocket.localPort
      } else {
        this.port
      }

      do {
        val exitCode: Int? = launchServer(serverSocket, logOutputStream, port)
        if (exitCode != null) return exitCode
      } while (this.endless)
    } finally {
      serverSocket?.close()

      if (logOutputStream != null) {
        logOutputStream.flush()
        logOutputStream.close()
      }
    }

    return 0
  }

  private fun setupLogFileOutput(): OutputStream? {
    var logFile: File = this.logFile?.toFile() ?: return null

    if (logFile.exists() && logFile.isDirectory) {
      logFile = File(logFile, "ltex-ls-\${PID}.log")
    }

    val logFileString: String =
        logFile.absolutePath.replace("\${PID}", ProcessHandle.current().pid().toString())
    val logOutputStream: OutputStream = FileOutputStream(logFileString, true)
    System.setErr(
      PrintStream(TeeOutputStream(System.err, logOutputStream), true, StandardCharsets.UTF_8),
    )
    return logOutputStream
  }

  private fun launchServer(
    serverSocket: ServerSocket?,
    logOutputStream: OutputStream?,
    port: Int,
  ): Int? {
    var inputStream: InputStream = System.`in`
    var outputStream: OutputStream = System.out

    if (this.serverType == ServerType.TcpSocket) {
      if (serverSocket == null) throw NullPointerException("serverSocket")
      Logging.LOGGER.info(I18n.format("waitingForClientToConnectOnPort", port))
      val clientSocket: Socket = serverSocket.accept()
      Logging.LOGGER.info(I18n.format("connectedToClientOnPort", port))
      inputStream = clientSocket.getInputStream()
      outputStream = clientSocket.getOutputStream()
    }

    if (logOutputStream != null) {
      inputStream = TeeInputStream(inputStream, logOutputStream)
      outputStream = TeeOutputStream(outputStream, logOutputStream)
    }

    launch(inputStream, outputStream)
    return null
  }

  enum class ServerType {
    StandardStream,
    TcpSocket,
  }

  companion object {
    private const val SERVER_SOCKET_BACKLOG_SIZE = 50

    @JvmStatic
    @Suppress("SpreadOperator")
    fun main(arguments: Array<String>) {
      AnsiConsole.systemInstall()
      val commandLine =
          CommandLine(LtexLanguageServerLauncher()).setCaseInsensitiveEnumValuesAllowed(true)
      val exitCode: Int = commandLine.execute(*arguments)
      if (exitCode != 0) exitProcess(exitCode)
    }

    @Suppress("SpreadOperator")
    fun mainWithoutExit(arguments: Array<String>): Int {
      val launcher = LtexLanguageServerLauncher()
      val commandLine = CommandLine(launcher).setCaseInsensitiveEnumValuesAllowed(true)
      commandLine.parseArgs(*arguments)

      return when {
        commandLine.isUsageHelpRequested -> {
          commandLine.usage(commandLine.out)
          commandLine.commandSpec.exitCodeOnUsageHelp()
        }
        commandLine.isVersionHelpRequested -> {
          commandLine.printVersionHelp(commandLine.out)
          commandLine.commandSpec.exitCodeOnVersionHelp()
        }
        else -> {
          val exitCode: Int = launcher.call()
          commandLine.setExecutionResult(exitCode)
          commandLine.commandSpec.exitCodeOnSuccess()
        }
      }
    }

    fun launch(inputStream: InputStream, outputStream: OutputStream) {
      val server = LtexLanguageServer()
      val executorService: ExecutorService = Executors.newSingleThreadScheduledExecutor()

      val launcherBuilder = LSPLauncher.Builder<LtexLanguageClient>()
      launcherBuilder.setLocalService(server)
      launcherBuilder.setRemoteInterface(LtexLanguageClient::class.javaObjectType)
      launcherBuilder.setInput(inputStream)
      launcherBuilder.setOutput(outputStream)
      launcherBuilder.setExecutorService(executorService)

      val launcher: Launcher<LtexLanguageClient> = launcherBuilder.create()

      val client: LanguageClient = launcher.remoteProxy
      server.connect(client)

      val listener: Future<Void> = launcher.startListening()
      executorService.shutdown()
      listener.get()
    }
  }
}
