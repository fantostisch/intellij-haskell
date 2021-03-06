/*
 * Copyright 2014-2020 Rik van der Kleij

 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package intellij.haskell.external.execution

import java.nio.charset.Charset

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.GeneralCommandLine.ParentEnvironmentType
import com.intellij.execution.process
import com.intellij.execution.process._
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.util.io.BaseOutputReader
import intellij.haskell.{GlobalInfo, HaskellNotificationGroup}
import org.jetbrains.jps.incremental.messages.BuildMessage
import org.jetbrains.jps.incremental.messages.BuildMessage.Kind

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

object CommandLine {
  val DefaultTimeout: FiniteDuration = 60.seconds
  val DefaultNotifyBalloonError = false
  val DefaultIgnoreExitCode = false
  val DefaultLogOutput = false

  def run(project: Project, commandPath: String, arguments: Seq[String], timeoutInMillis: Long = DefaultTimeout.toMillis,
          notifyBalloonError: Boolean = DefaultNotifyBalloonError, ignoreExitCode: Boolean = DefaultIgnoreExitCode,
          logOutput: Boolean = DefaultLogOutput, charset: Option[Charset] = None): ProcessOutput = {
    run3(Some(project), project.getBasePath, commandPath, arguments, timeoutInMillis, notifyBalloonError, ignoreExitCode,
      logOutput, charset)
  }

  def runInWorkDir(project: Project, workDir: String, commandPath: String, arguments: Seq[String], timeoutInMillis: Long = DefaultTimeout.toMillis,
                   notifyBalloonError: Boolean = DefaultNotifyBalloonError, ignoreExitCode: Boolean = DefaultIgnoreExitCode,
                   logOutput: Boolean = DefaultLogOutput, charset: Option[Charset] = None): ProcessOutput = {
    run3(Some(project), workDir, commandPath, arguments, timeoutInMillis, notifyBalloonError, ignoreExitCode,
      logOutput, charset)
  }

  def runInHomeDir(commandPath: String, arguments: Seq[String], timeoutInMillis: Long = DefaultTimeout.toMillis,
                   notifyBalloonError: Boolean = DefaultNotifyBalloonError, ignoreExitCode: Boolean = DefaultIgnoreExitCode,
                   logOutput: Boolean = DefaultLogOutput, charset: Option[Charset] = None): ProcessOutput = {
    run3(None, VfsUtil.getUserHomeDir.getPath, commandPath, arguments, timeoutInMillis, notifyBalloonError, ignoreExitCode,
      logOutput, charset)
  }

  def runWithProgressIndicator(project: Project, workDir: Option[String], commandPath: String, arguments: Seq[String],
                               progressIndicator: Option[ProgressIndicator], charset: Option[Charset] = None): CapturingProcessHandler = {
    val commandLine = createCommandLine(workDir.getOrElse(project.getBasePath), commandPath, arguments, charset)

    new CapturingProcessHandler(commandLine) {
      override protected def createProcessAdapter(processOutput: ProcessOutput): CapturingProcessAdapter = {
        progressIndicator match {
          case Some(pi) => new CapturingProcessToProgressIndicator(project, pi)
          case None => super.createProcessAdapter(processOutput)
        }
      }

      override def readerOptions(): BaseOutputReader.Options = {
        BaseOutputReader.Options.forMostlySilentProcess()
      }
    }
  }

  private def run3(project: Option[Project], workDir: String, commandPath: String, arguments: Seq[String], timeoutInMillis: Long = DefaultTimeout.toMillis,
                   notifyBalloonError: Boolean = DefaultNotifyBalloonError, ignoreExitCode: Boolean = DefaultIgnoreExitCode,
                   logOutput: Boolean = DefaultLogOutput, charset: Option[Charset] = None): ProcessOutput = {

    val commandLine = createCommandLine(workDir, commandPath, arguments, charset)

    if (!logOutput) {
      HaskellNotificationGroup.logInfoEvent(project, s"Executing: ${commandLine.getCommandLineString} ")
    }

    val processHandler = createProcessHandler(project, commandLine, logOutput)

    val processOutput = processHandler.map(_.runProcess(timeoutInMillis.toInt, true)).getOrElse(new process.ProcessOutput(-1))

    if (processOutput.isTimeout) {
      val message = s"Timeout while executing `${commandLine.getCommandLineString}`"
      if (notifyBalloonError) {
        HaskellNotificationGroup.logErrorBalloonEvent(project, message)
      } else {
        HaskellNotificationGroup.logErrorEvent(project, message)
      }
      processOutput
    } else if (!ignoreExitCode && processOutput.getExitCode != 0) {
      val errorMessage = createLogMessage(commandLine, processOutput)
      val message = s"Executing `${commandLine.getCommandLineString}` failed: $errorMessage"
      if (notifyBalloonError) HaskellNotificationGroup.logErrorBalloonEvent(project, message) else HaskellNotificationGroup.logErrorEvent(project, message)
      processOutput
    } else {
      processOutput
    }
  }

  def createCommandLine(workDir: String, commandPath: String, arguments: Seq[String], charset: Option[Charset] = None): GeneralCommandLine = {
    val commandLine = new GeneralCommandLine
    commandLine.withWorkDirectory(workDir)
    commandLine.setExePath(commandPath)
    commandLine.addParameters(arguments.asJava)
    commandLine.withParentEnvironmentType(ParentEnvironmentType.CONSOLE)
    commandLine.withEnvironment(GlobalInfo.pathVariables)
    charset.foreach(commandLine.setCharset)
    commandLine
  }

  private def createProcessHandler(project: Option[Project], cmd: GeneralCommandLine, logOutput: Boolean): Option[CapturingProcessHandler] = {
    try {
      if (logOutput) {
        Some(
          new CapturingProcessHandler(cmd) {
            override protected def createProcessAdapter(processOutput: ProcessOutput): CapturingProcessAdapter = new CapturingProcessToLog(project, cmd, processOutput)
          })
      } else {
        Some(new CapturingProcessHandler(cmd))
      }
    } catch {
      case e: ProcessNotCreatedException =>
        HaskellNotificationGroup.logErrorBalloonEvent(project, e.getMessage)
        None
    }
  }

  private def createLogMessage(cmd: GeneralCommandLine, processOutput: ProcessOutput): String = {
    s"${cmd.getCommandLineString}:  ${processOutput.getStdoutLines.asScala.mkString("\n")} \n ${processOutput.getStderrLines.asScala.mkString("\n")}"
  }
}

object AnsiDecoder {
  def decodeAnsiCommandsToString(ansi: String, outputType: Key[_], decoder: AnsiEscapeDecoder): String = {
    val buffer = new StringBuilder()
    decoder.escapeText(ansi, outputType, (text, _) => buffer.append(text))
    buffer.result()
  }
}

private class HaskellBuildMessage(message: String, kind: Kind) extends BuildMessage(message, kind)

private class CapturingProcessToLog(val project: Option[Project], val cmd: GeneralCommandLine, val output: ProcessOutput) extends CapturingProcessAdapter(output) {

  override def onTextAvailable(event: ProcessEvent, outputType: Key[_]): Unit = {
    super.onTextAvailable(event, outputType)
    addToLog(event.getText, outputType)
  }

  private def addToLog(text: String, outputType: Key[_]): Unit = {
    val trimmedText = text.trim
    if (trimmedText.nonEmpty) {
      HaskellNotificationGroup.logInfoEvent(project, s"${cmd.getCommandLineString}:  $trimmedText")
    }
  }
}

private class CapturingProcessToProgressIndicator(project: Project, progressIndicator: ProgressIndicator) extends CapturingProcessAdapter() {

  private val ansiEscapeDecoder = new AnsiEscapeDecoder()

  override def onTextAvailable(event: ProcessEvent, outputType: Key[_]): Unit = {
    val text = AnsiDecoder.decodeAnsiCommandsToString(event.getText, outputType, ansiEscapeDecoder)
    if (!StringUtil.isEmptyOrSpaces(text)) {
      progressIndicator.setText2(text)
      HaskellNotificationGroup.logInfoEvent(project, text)
    }
  }
}


sealed trait CaptureOutput

object CaptureOutputToLog extends CaptureOutput

