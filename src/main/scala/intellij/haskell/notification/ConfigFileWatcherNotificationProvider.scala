package intellij.haskell.notification

import java.util
import java.util.concurrent.ConcurrentHashMap

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.util.ReadTask.Continuation
import com.intellij.openapi.progress.util.{ProgressIndicatorUtils, ReadTask}
import com.intellij.openapi.project.{DumbService, Project}
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.{VFileContentChangeEvent, VFileEvent}
import com.intellij.openapi.vfs.{VirtualFile, VirtualFileManager}
import com.intellij.ui.{EditorNotificationPanel, EditorNotifications}
import intellij.haskell.external.component.StackProjectManager
import intellij.haskell.util.{HaskellFileUtil, HaskellProjectUtil}

import scala.collection.JavaConverters._
import scala.collection.concurrent

object ConfigFileWatcherNotificationProvider {
  private val ConfigFileWatcherKey: Key[EditorNotificationPanel] = Key.create("Haskell config file watcher")
  val showNotificationsByProject: concurrent.Map[Project, Boolean] = new ConcurrentHashMap[Project, Boolean]().asScala
}

class ConfigFileWatcherNotificationProvider(project: Project, notifications: EditorNotifications) extends EditorNotifications.Provider[EditorNotificationPanel] {
  project.getMessageBus.connect(project).subscribe(VirtualFileManager.VFS_CHANGES, new ConfigFileWatcher(project, notifications))

  override def getKey: Key[EditorNotificationPanel] = ConfigFileWatcherNotificationProvider.ConfigFileWatcherKey

  override def createNotificationPanel(virtualFile: VirtualFile, fileEditor: FileEditor): EditorNotificationPanel = {
    if (HaskellProjectUtil.isHaskellProject(project) && ConfigFileWatcherNotificationProvider.showNotificationsByProject.get(project).contains(true)) {
      createPanel(project, virtualFile)
    } else {
      null
    }
  }

  private def createPanel(project: Project, file: VirtualFile): EditorNotificationPanel = {
    val panel = new EditorNotificationPanel
    panel.setText("Haskell project configuration file is updated")
    panel.createActionLabel("Update Settings and restart REPLs", () => {
      ConfigFileWatcherNotificationProvider.showNotificationsByProject.put(project, false)
      notifications.updateAllNotifications()
      StackProjectManager.restart(project)
    })
    panel.createActionLabel("Ignore", () => {
      ConfigFileWatcherNotificationProvider.showNotificationsByProject.put(project, false)
      notifications.updateAllNotifications()
    })
    panel
  }
}

private class ConfigFileWatcher(project: Project, notifications: EditorNotifications) extends BulkFileListener {

  import scala.collection.JavaConverters._

  private val watchFiles = HaskellProjectUtil.findStackFile(project).toIterable ++ HaskellProjectUtil.findCabalFiles(project) ++ HaskellProjectUtil.findPackageFiles(project)

  override def before(events: util.List[_ <: VFileEvent]): Unit = {}

  override def after(events: util.List[_ <: VFileEvent]): Unit = {
    if (!StackProjectManager.isBuilding(project)) {
      val readTask = new ReadTask {

        override def runBackgroundProcess(indicator: ProgressIndicator): Continuation = {
          DumbService.getInstance(project).runReadActionInSmartMode(() => {
            performInReadAction(indicator)
          })
        }

        override def computeInReadAction(indicator: ProgressIndicator): Unit = {
          if (!project.isDisposed) {
            indicator.checkCanceled()
            if (events.asScala.exists(e => e.isInstanceOf[VFileContentChangeEvent] && !e.isFromRefresh && watchFiles.exists(_.getAbsolutePath == HaskellFileUtil.getAbsolutePath(e.getFile)))) {
              ConfigFileWatcherNotificationProvider.showNotificationsByProject.put(project, true)
              notifications.updateAllNotifications()
            }
          }
        }

        override def onCanceled(indicator: ProgressIndicator): Unit = {
          ProgressIndicatorUtils.scheduleWithWriteActionPriority(this)
        }
      }

      ProgressIndicatorUtils.scheduleWithWriteActionPriority(readTask)
    }
  }
}
