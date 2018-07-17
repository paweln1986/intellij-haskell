/*
 * Copyright 2014-2018 Rik van der Kleij
 *
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

package intellij.haskell.external.component

import java.io.File
import java.nio.file.Paths

import com.intellij.execution.process.ProcessOutput
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.project.Project
import intellij.haskell.external.execution.{CommandLine, StackCommandLine}
import intellij.haskell.psi.{HaskellPsiUtil, HaskellQualifiedNameElement}
import intellij.haskell.util.{HaskellProjectUtil, HtmlElement}
import intellij.haskell.{GlobalInfo, HaskellNotificationGroup}

import scala.collection.JavaConverters._

object HoogleComponent {

  final val HoogleName = "hoogle"
  private final val HooglePath = GlobalInfo.toolPath(HoogleName).toString
  private final val HoogleDbName = "hoogle"

  def runHoogle(project: Project, pattern: String, count: Int = 100): Option[Seq[String]] = {
    if (isHoogleFeatureAvailable(project)) {
      runHoogle(project, Seq( s""""$pattern"""", s"--count=$count")).
        map(o =>
          if (o.getStdoutLines.isEmpty || o.getStdout.contains("No results found"))
            Seq()
          else if (o.getStdoutLines.asScala.last.startsWith("-- ")) {
            o.getStdoutLines.asScala.init
          } else {
            o.getStdoutLines.asScala
          }
        )
    } else {
      None
    }
  }

  def findDocumentation(project: Project, qualifiedNameElement: HaskellQualifiedNameElement): Option[String] = {
    if (isHoogleFeatureAvailable(project)) {
      val name = qualifiedNameElement.getIdentifierElement.getName
      Option(qualifiedNameElement.getContainingFile).flatMap { psiFile =>
        if (HaskellProjectUtil.isProjectFile(psiFile)) {
          DefinitionLocationComponent.findDefinitionLocation(psiFile, qualifiedNameElement, isCurrentFile = true) match {
            case Left(noInfo) =>
              HaskellNotificationGroup.logWarningEvent(project, s"No documentation because no location info could be found for identifier `$name` because ${noInfo.message}")
              None
            case Right(info) =>
              info.moduleName match {
                case None =>
                  HaskellNotificationGroup.logWarningEvent(project, s"No documentation because could not find module for identifier `$name`")
                  None
                case Some(moduleName) => HoogleComponent.createDocumentation(project, name, moduleName)
              }
          }
        } else {
          val moduleName = HaskellPsiUtil.findModuleName(psiFile)
          moduleName.flatMap(mn => createDocumentation(project, name, mn))
        }
      }
    } else {
      Some("No documentation because Hoogle (database) is not available")
    }
  }

  private def createDocumentation(project: Project, name: String, moduleName: String): Option[String] = {
    def mkString(lines: Seq[String]) = {
      lines.mkString("\n").
        replace("<", HtmlElement.Lt).
        replace(">", HtmlElement.Gt)
    }

    runHoogle(project, Seq(name, "-i", s"+$moduleName")).
      flatMap(processOutput =>
        if (processOutput.getStdoutLines.isEmpty || processOutput.getStdout.contains("No results found")) {
          None
        } else {
          val output = processOutput.getStdoutLines
          val (definition, content) = output.asScala.splitAt(2)
          Some(
            DocumentationMarkup.DEFINITION_START +
              mkString(definition) +
              DocumentationMarkup.DEFINITION_END +
              DocumentationMarkup.CONTENT_START +
              HtmlElement.PreStart +
              mkString(content) +
              HtmlElement.PreEnd +
              DocumentationMarkup.CONTENT_END
          )
        }
      )
  }

  private def isHoogleFeatureAvailable(project: Project): Boolean = {
    if (!StackProjectManager.isHoogleAvailable(project)) {
      HaskellNotificationGroup.logInfoEvent(project, s"$HoogleName is not (yet) available")
      false
    } else {
      doesHoogleDatabaseExist(project)
    }
  }

  def rebuildHoogle(project: Project): Unit = {
    val buildHaddockOutput = StackCommandLine.executeInMessageView(project, Seq("haddock", "--test", "--bench", "--no-run-tests", "--no-run-benchmarks"))
    if (buildHaddockOutput.contains(true)) {
      StackCommandLine.executeInMessageView(project, Seq("exec", "--", HooglePath, "generate", "--local", s"--database=${hoogleDbPath(project)}"))
    }
  }

  def doesHoogleDatabaseExist(project: Project): Boolean = {
    new File(hoogleDbPath(project)).exists()
  }

  def showHoogleDatabaseDoesNotExistNotification(project: Project): Unit = {
    HaskellNotificationGroup.logInfoBalloonEvent(project, "Hoogle database does not exist. Hoogle features can be optionally enabled by menu option `Tools`/`Haskell`/`(Re)Build Hoogle database`")
  }

  def versionInfo(project: Project): String = {
    CommandLine.run(Some(project), project.getBasePath, HooglePath, Seq("--version")).getStdout
  }

  private def runHoogle(project: Project, arguments: Seq[String]): Option[ProcessOutput] = {
    StackCommandLine.run(project, Seq("exec", "--", HooglePath, s"--database=${hoogleDbPath(project)}") ++ arguments, logOutput = true)
  }

  private def hoogleDbPath(project: Project) = {
    Paths.get(project.getBasePath, GlobalInfo.StackWorkDirName, HoogleDbName).toString
  }
}
