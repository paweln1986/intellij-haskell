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

import java.util.concurrent.TimeUnit

import com.github.blemale.scaffeine.{AsyncLoadingCache, Scaffeine}
import com.intellij.openapi.editor.SelectionModel
import com.intellij.openapi.project.Project
import com.intellij.psi.{PsiElement, PsiFile}
import intellij.haskell.external.repl.StackReplsManager
import intellij.haskell.psi._
import intellij.haskell.util.{ApplicationUtil, LineColumnPosition}

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future, TimeoutException}

private[component] object TypeInfoComponent {

  import intellij.haskell.external.component.TypeInfoComponentResult._

  private case class Key(moduleName: Option[String], psiFile: PsiFile, qualifiedNameElement: HaskellQualifiedNameElement, expression: String)

  private final val Cache: AsyncLoadingCache[Key, TypeInfoResult] = Scaffeine().buildAsync((k: Key) => findTypeInfoResult(k))

  def findTypeInfoForElement(element: PsiElement): TypeInfoResult = {
    if (ApplicationUtil.runReadAction(element.isValid)) {
      (for {
        qne <- ApplicationUtil.runReadAction(HaskellPsiUtil.findQualifiedNameParent(element))
        pf <- ApplicationUtil.runReadAction(Option(element.getContainingFile))
      } yield {
        val moduleName = HaskellPsiUtil.findModuleName(pf)
        Key(moduleName, pf, qne, qne.getName)
      }).map(findTypeInfo).getOrElse(Left(NoInfoAvailable))
    } else {
      Left(NoInfoAvailable)
    }
  }

  def findTypeInfoForSelection(psiFile: PsiFile, selectionModel: SelectionModel): TypeInfoResult = {
    val moduleName = HaskellPsiUtil.findModuleName(psiFile)
    if (LoadComponent.isModuleLoaded(moduleName, psiFile)) {
      {
        for {
          sp <- ApplicationUtil.runReadAction(LineColumnPosition.fromOffset(psiFile, selectionModel.getSelectionStart))
          ep <- ApplicationUtil.runReadAction(LineColumnPosition.fromOffset(psiFile, selectionModel.getSelectionEnd))
        } yield {
          StackReplsManager.getProjectRepl(psiFile).flatMap(_.findTypeInfo(moduleName, psiFile, sp.lineNr, sp.columnNr, ep.lineNr, ep.columnNr, selectionModel.getSelectedText)) match {
            case Some(output) => output.stdoutLines.headOption.filterNot(_.trim.isEmpty).map(ti => Right(TypeInfo(ti, output.stderrLines.nonEmpty))).getOrElse(Left(NoInfoAvailable))
            case None => Left(ReplNotAvailable)
          }
        }
      }.getOrElse(Left(NoInfoAvailable))
    } else {
      Left(NoInfoAvailable)
    }
  }

  def invalidate(psiFile: PsiFile): Unit = {
    val keys = Cache.synchronous().asMap().filter(_._1.psiFile == psiFile).flatMap { case (k, v) =>
      v.toOption match {
        case Some(_) =>
          val sameFile = DefinitionLocationComponent.findDefinitionLocation(psiFile, k.qualifiedNameElement, isCurrentFile = false).toOption match {
            case Some(definitionLocation) => Option(definitionLocation.namedElement.getContainingFile).contains(k.psiFile)
            case None => false
          }

          if (!ApplicationUtil.runReadAction(k.qualifiedNameElement.isValid) || sameFile) {
            Some(k)
          } else {
            None
          }
        case None => Some(k)
      }
    }

    Cache.synchronous().invalidateAll(keys)
  }

  def invalidate(moduleName: String): Unit = {
    val keys = Cache.synchronous().asMap().flatMap { case (k, v) =>
      v.toOption match {
        case Some(_) =>
          val sameModule = DefinitionLocationComponent.findDefinitionLocation(k.psiFile, k.qualifiedNameElement, isCurrentFile = false).toOption match {
            case Some(definitionLocation) => definitionLocation.moduleName.contains(moduleName)
            case None => false
          }

          if (sameModule) {
            Some(k)
          } else {
            None
          }
        case None => None
      }
    }

    Cache.synchronous().invalidateAll(keys)
  }

  def invalidateAll(project: Project): Unit = {
    Cache.synchronous().asMap().filter(_._1.psiFile.getProject == project).keys.foreach(Cache.synchronous().invalidate)
  }

  private def findTypeInfoResult(key: Key): TypeInfoResult = {
    val psiFile = key.psiFile
    if (LoadComponent.isBusy(psiFile)) {
      Left(ReplIsBusy)
    } else {
      {
        val qne = key.qualifiedNameElement
        val project = key.psiFile.getProject
        val to = ApplicationUtil.runReadActionWithWriteActionPriority(project, qne.getTextOffset)
        for {
          sp <- ApplicationUtil.runReadActionWithWriteActionPriority(project, LineColumnPosition.fromOffset(psiFile, to))
          ep <- ApplicationUtil.runReadActionWithWriteActionPriority(project, LineColumnPosition.fromOffset(psiFile, to + qne.getText.length))
        } yield {

          StackReplsManager.getProjectRepl(key.psiFile).flatMap(_.findTypeInfo(key.moduleName, key.psiFile, sp.lineNr, sp.columnNr, ep.lineNr, ep.columnNr, key.expression)) match {
            case Some(output) => output.stdoutLines.headOption.filterNot(_.trim.isEmpty).map(ti => Right(TypeInfo(ti, output.stderrLines.nonEmpty))).getOrElse(Left(NoInfoAvailable))
            case None => Left(ReplNotAvailable)
          }
        }
      }.getOrElse(Left(NoInfoAvailable))
    }
  }

  private def findTypeInfo(key: Key): TypeInfoResult = {
    if (LoadComponent.isModuleLoaded(key.moduleName, key.psiFile)) {
      val result = wait(Cache.get(key))
      result match {
        case Right(_) => result
        case Left(NoInfoAvailable) =>
          result
        case Left(ReplNotAvailable) | Left(ReplIsBusy) | Left(IndexNotReady) =>
          Cache.synchronous().invalidate(key)
          result
      }
    } else {
      Left(NoInfoAvailable)
    }
  }

  private final val Timeout = Duration.create(100, TimeUnit.MILLISECONDS)

  private def wait(f: => Future[TypeInfoResult]) = {
    try {
      Await.result(f, Timeout)
    } catch {
      case _: TimeoutException => Left(ReplIsBusy)
    }
  }
}

object TypeInfoComponentResult {

  type TypeInfoResult = Either[NoInfo, TypeInfo]

  case class TypeInfo(typeSignature: String, withFailure: Boolean)

}
