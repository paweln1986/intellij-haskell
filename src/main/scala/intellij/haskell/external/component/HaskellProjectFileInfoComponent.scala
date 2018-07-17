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

import java.nio.file.Paths

import com.github.blemale.scaffeine.{LoadingCache, Scaffeine}
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.PsiFile
import intellij.haskell.HaskellNotificationGroup
import intellij.haskell.external.repl.StackReplsManager
import intellij.haskell.external.repl.StackReplsManager.StackComponentInfo
import intellij.haskell.runconfig.console.HaskellConsoleView
import intellij.haskell.util.{HaskellFileUtil, ScalaUtil}

private[component] object HaskellProjectFileInfoComponent {

  private case class Key(project: Project, filePath: String)

  private final val Cache: LoadingCache[Key, Option[HaskellProjectFileInfo]] = Scaffeine().build((k: Key) => createFileInfo(k))

  def findHaskellProjectFileInfo(project: Project, filePath: String): Option[HaskellProjectFileInfo] = {
    val key = Key(project, filePath)
    Cache.get(key) match {
      case result@Some(_) => result
      case _ =>
        Cache.invalidate(key)
        None
    }
  }

  def findHaskellProjectFileInfo(psiFile: PsiFile): Option[HaskellProjectFileInfo] = {
    val project = psiFile.getProject

    HaskellConsoleView.findConsoleInfo(psiFile) match {
      case Some(consoleInfo) =>
        val stackComponentInfos = StackReplsManager.getReplsManager(project).map(_.stackComponentInfos)
        stackComponentInfos.flatMap(_.find(_.target == consoleInfo.stackTarget)).map(HaskellProjectFileInfo)
      case None =>
        val key = HaskellFileUtil.getAbsolutePath(psiFile).map(fp => Key(psiFile.getProject, fp))
        key.flatMap(k => Cache.get(k) match {
          case result@Some(_) => result
          case _ =>
            Cache.invalidate(k)
            None
        })
    }
  }

  def invalidate(project: Project): Unit = {
    val keys = Cache.asMap().keys.filter(_.project == project)
    keys.foreach(Cache.invalidate)
  }

  def invalidate(psiFile: PsiFile): Unit = {
    HaskellFileUtil.getAbsolutePath(psiFile).foreach(fp => Cache.invalidate(Key(psiFile.getProject, fp)))
  }

  private def createFileInfo(key: Key): Option[HaskellProjectFileInfo] = {
    val project = key.project
    val filePath = key.filePath

    StackReplsManager.getReplsManager(project).map(_.stackComponentInfos).flatMap(stackComponentInfos => {
      getStackComponentInfo(project, filePath, stackComponentInfos).map(buildInfo => HaskellProjectFileInfo(buildInfo))
    })
  }

  private def getStackComponentInfo(project: Project, filePath: String, stackTargetBuildInfos: Iterable[StackComponentInfo]): Option[StackComponentInfo] = {
    stackTargetBuildInfos.find(_.mainIs.exists(filePath.contains)) match {
      case info@Some(_) => info
      case None =>
        val sourceDirsByInfo = stackTargetBuildInfos.map(info => (info, info.sourceDirs.filter(sd => FileUtil.isAncestor(sd, filePath, true)))).filterNot({ case (_, sd) => sd.isEmpty })
        val stackComponentInfo = if (sourceDirsByInfo.size > 1) {
          val sourceDirByInfo = sourceDirsByInfo.map({ case (info, sds) => (info, sds.maxBy(sd => Paths.get(sd).getNameCount)) })
          val mostSpecificSourceDirByInfo = ScalaUtil.maxsBy(sourceDirByInfo)({ case (_, sd) => Paths.get(sd).getNameCount })
          if (mostSpecificSourceDirByInfo.size > 1) {
            HaskellNotificationGroup.logWarningBalloonEvent(project, s"Ambiguous Stack target for file `$filePath`. It can belong to the source dir of more than one Stack target/Cabal stanza. The first one of `${mostSpecificSourceDirByInfo.map(_._1.target)}` is chosen.")
          }
          mostSpecificSourceDirByInfo.headOption.map(_._1)
        } else {
          sourceDirsByInfo.headOption.map(_._1)
        }
        stackComponentInfo match {
          case info@Some(_) => info
          case None =>
            HaskellNotificationGroup.logErrorBalloonEvent(project, s"Could not determine Stack target for file `$filePath` because no accompanying `hs-source-dirs` or `main-is` can be found in Cabal file(s)")
            None
        }
    }
  }
}

case class HaskellProjectFileInfo(stackComponentInfo: StackComponentInfo)

