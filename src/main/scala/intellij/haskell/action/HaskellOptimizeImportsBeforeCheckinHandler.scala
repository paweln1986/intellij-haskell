/*
 * Copyright 2000-2015 JetBrains s.r.o.
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

package intellij.haskell.action

import java.awt.GridLayout

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.{DumbService, Project}
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.checkin._
import com.intellij.openapi.vcs.ui.RefreshableOnComponent
import com.intellij.ui.NonFocusableCheckBox
import intellij.haskell.editor.HaskellImportOptimizer
import intellij.haskell.settings.HaskellSettingsState
import intellij.haskell.util.HaskellFileUtil
import javax.swing.{JCheckBox, JComponent, JPanel}

class HaskellOptimizeImportsBeforeCheckinHandler(project: Project, checkinProjectPanel: CheckinProjectPanel) extends CheckinHandler with CheckinMetaHandler {

  override def getBeforeCheckinConfigurationPanel: RefreshableOnComponent = {
    val optimizeBox = new NonFocusableCheckBox("Optimize imports by GHC warnings")
    disableWhenDumb(project, optimizeBox, "Impossible until indices are up-to-date")
    new RefreshableOnComponent() {
      override def getComponent: JComponent = {
        val panel = new JPanel(new GridLayout(1, 0))
        panel.add(optimizeBox)
        panel
      }

      override def refresh(): Unit = {
      }

      override def saveState(): Unit = {
        HaskellSettingsState.setOptimizeImportsBeforeCommit(optimizeBox.isSelected)
      }

      override def restoreState(): Unit = {
        optimizeBox.setSelected(HaskellSettingsState.isOptmizeImportsBeforeCommit)
      }
    }
  }

  override def runCheckinHandlers(finishAction: Runnable): Unit = {
    import scala.collection.JavaConverters._
    val virtualFiles = checkinProjectPanel.getVirtualFiles

    val performCheckoutAction: Runnable = () => {
      FileDocumentManager.getInstance.saveAllDocuments()
      finishAction.run()
    }

    if (HaskellSettingsState.isReformatCodeBeforeCommit && !DumbService.isDumb(project)) {
      val reformatResult = virtualFiles.asScala.forall(vf => HaskellFileUtil.convertToHaskellFile(project, vf).exists(HaskellImportOptimizer.removeRedundantImports))
      if (reformatResult) {
        performCheckoutAction.run()
      }
    } else {
      performCheckoutAction.run()
    }
  }


  private def disableWhenDumb(project: Project, checkBox: JCheckBox, tooltip: String) = {
    val dumb = DumbService.isDumb(project)
    checkBox.setEnabled(!dumb)
    checkBox.setToolTipText(if (dumb) tooltip else "")
  }
}
