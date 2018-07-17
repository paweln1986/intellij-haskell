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

import com.github.blemale.scaffeine.{LoadingCache, Scaffeine}
import com.intellij.openapi.project.Project
import intellij.haskell.external.execution.{CommandLine, StackCommandLine}

import scala.collection.JavaConverters._

private[component] object GlobalProjectInfoComponent {

  private case class Key(project: Project)

  private final val Cache: LoadingCache[Key, Option[GlobalProjectInfo]] = Scaffeine().build((k: Key) => createGlobalProjectInfo(k))

  def findGlobalProjectInfo(project: Project): Option[GlobalProjectInfo] = {
    val key = Key(project)
    Cache.get(key) match {
      case result@Some(_) => result
      case _ =>
        Cache.invalidate(key)
        None
    }
  }

  def getSupportedLanguageExtensions(project: Project): Option[Iterable[String]] = {
    findGhcPath(project).map(ghcPath => {
      CommandLine.run(
        Some(project),
        project.getBasePath,
        ghcPath,
        Seq("--supported-languages"),
        notifyBalloonError = true
      ).getStdoutLines.asScala
    })
  }

  def getAvailablePackages(project: Project): Iterable[String] = {
    CabalConfigComponent.getAvailablePackageNames(project)
  }

  def invalidate(project: Project): Unit = {
    val keys = Cache.asMap().keys.filter(_.project == project)
    keys.foreach(Cache.invalidate)
  }

  private def createGlobalProjectInfo(key: Key): Option[GlobalProjectInfo] = {
    val project = key.project
    val extensions = getSupportedLanguageExtensions(project)
    val packageNames = getAvailablePackages(project)
    extensions.map(exts => GlobalProjectInfo(exts, packageNames))
  }

  private def findGhcPath(project: Project) = {
    StackCommandLine.run(project, Seq("path", "--compiler-exe")).flatMap(_.getStdoutLines.asScala.headOption)
  }
}

case class GlobalProjectInfo(supportedLanguageExtensions: Iterable[String], availablePackageNames: Iterable[String])
