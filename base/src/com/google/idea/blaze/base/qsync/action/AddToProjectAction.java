/*
 * Copyright 2023 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.base.qsync.action;

import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import com.google.idea.blaze.base.actions.BlazeProjectAction;
import com.google.idea.blaze.base.logging.utils.querysync.QuerySyncActionStatsScope;
import com.google.idea.blaze.base.model.primitives.WorkspacePath;
import com.google.idea.blaze.base.model.primitives.WorkspaceRoot;
import com.google.idea.blaze.base.projectview.ProjectViewEdit;
import com.google.idea.blaze.base.projectview.section.ListSection;
import com.google.idea.blaze.base.projectview.section.sections.DirectoryEntry;
import com.google.idea.blaze.base.projectview.section.sections.DirectorySection;
import com.google.idea.blaze.base.qsync.CandidatePackageFinder;
import com.google.idea.blaze.base.qsync.CandidatePackageFinder.CandidatePackage;
import com.google.idea.blaze.base.qsync.QuerySyncManager;
import com.google.idea.blaze.base.qsync.QuerySyncProject;
import com.google.idea.blaze.exception.BuildException;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task.WithResult;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.ui.popup.PopupStep;
import com.intellij.openapi.ui.popup.util.BaseListPopupStep;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;
import org.jetbrains.annotations.NotNull;

/** Query sync specific action to add a new directory to the project view. */
public class AddToProjectAction extends BlazeProjectAction {

  private static final String NOTIFICATION_GROUP_ID = "AddToProject";

  private static final Logger logger = Logger.getInstance(AddToProjectAction.class);

  @Override
  @NotNull
  public ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.BGT;
  }

  @Override
  protected QuerySyncStatus querySyncSupport() {
    return QuerySyncStatus.REQUIRED;
  }

  @Override
  protected void actionPerformedInBlazeProject(Project project, AnActionEvent e) {
    new Performer(project, e).perform();
  }

  @Override
  protected void updateForBlazeProject(Project project, AnActionEvent e) {
    Performer performer = new Performer(project, e);
    Presentation presentation = e.getPresentation();
    if (!performer.canPerform()) {
      presentation.setEnabledAndVisible(false);
      return;
    }
    presentation.setEnabled(true);
  }

  static class Performer {
    private final Project project;
    private final AnActionEvent event;
    private final QuerySyncManager qsManager;
    private final Path workspacePathToAdd;

    Performer(Project project, AnActionEvent event) {
      this.project = project;
      this.event = event;
      qsManager = QuerySyncManager.getInstance(project);
      workspacePathToAdd =
          getWorkspaceFile(WorkspaceRoot.fromProject(project).path(), event).orElse(null);
    }

    private static Optional<Path> getWorkspaceFile(Path workspaceRoot, AnActionEvent event) {
      VirtualFile virtualFile = event.getData(CommonDataKeys.VIRTUAL_FILE);
      if (virtualFile == null) {
        return Optional.empty();
      }
      Path file = virtualFile.toNioPath();
      if (!file.startsWith(workspaceRoot)) {
        return Optional.empty();
      }
      return Optional.of(workspaceRoot.relativize(file));
    }

    @FormatMethod
    private void notify(NotificationType type, @FormatString String format, Object... args) {
      NotificationGroupManager.getInstance()
          .getNotificationGroup(NOTIFICATION_GROUP_ID)
          .createNotification(String.format(format, args), type)
          .notify(project);
    }

    boolean canPerform() {
      if (workspacePathToAdd == null) {
        return false;
      }
      QuerySyncProject qsProject = qsManager.getLoadedProject().orElse(null);
      if (qsProject == null) {
        return false;
      }
      if (qsProject.getProjectDefinition().isIncluded(workspacePathToAdd)) {
        return false;
      }
      return true;
    }

    void perform() {
      if (!canPerform()) {
        // This shouldn't happen, but could rarely if there's a race between updateForBlazeProject
        // and actionPerformedInBlazeProject which may be possible. Fail gracefully.
        logger.warn("Cannot perform AddToProjectAction in current state!");
        return;
      }
      QuerySyncProject qsProject = qsManager.getLoadedProject().orElseThrow();

      ApplicationManager.getApplication()
          .invokeLater(
              () -> {
                try {
                  ProgressManager progressManager = ProgressManager.getInstance();
                  ImmutableList<CandidatePackage> candidatePackages =
                      progressManager.run(
                          new WithResult<ImmutableList<CandidatePackage>, BuildException>(
                              project, "Finding packages", true) {
                            @Override
                            protected ImmutableList<CandidatePackage> compute(
                                @NotNull ProgressIndicator progressIndicator)
                                throws BuildException {
                              return CandidatePackageFinder.create(qsProject, project)
                                  .getCandidatePackages(
                                      workspacePathToAdd, progressIndicator::checkCanceled);
                            }
                          });
                  ListPopup popup =
                      JBPopupFactory.getInstance()
                          .createListPopup(
                              SelectPackagePopupStep.create(
                                  candidatePackages, Performer.this::doAddToProjectView));
                  popup.showInBestPositionFor(event.getDataContext());
                } catch (BuildException e) {
                  notify(
                      NotificationType.ERROR,
                      "Failed to query packages: %s",
                      Optional.ofNullable(e.getMessage()).orElse(e.getClass().getSimpleName()));
                  if (e.isIdeError()) {
                    logger.error("Failed to query packages", e);
                  }
                }
              });
    }

    private void doAddToProjectView(CandidatePackage chosen) {
      ProjectViewEdit edit =
          ProjectViewEdit.editLocalProjectView(
              project,
              builder -> {
                ListSection<DirectoryEntry> section = builder.getLast(DirectorySection.KEY);
                builder.replace(
                    section,
                    ListSection.update(DirectorySection.KEY, section)
                        .add(
                            DirectoryEntry.include(
                                WorkspacePath.createIfValid(chosen.path.toString()))));
                return true;
              });
      if (edit == null) {
        notify(NotificationType.ERROR, "Failed to update project view");
        logger.warn("Failed to update project view (adding " + chosen.path + " to project)");
        return;
      }
      edit.apply();
      notify(NotificationType.INFORMATION, "Added %s to project view; starting sync", chosen.path);
      QuerySyncManager.getInstance(project)
          .fullSync(new QuerySyncActionStatsScope(getClass(), event));
    }
  }

  static class SelectPackagePopupStep extends BaseListPopupStep<CandidatePackage> {
    static SelectPackagePopupStep create(
        ImmutableList<CandidatePackage> candidates, Consumer<CandidatePackage> onChosen) {

      return new SelectPackagePopupStep(candidates, onChosen);
    }

    private final Consumer<CandidatePackage> onChosen;

    SelectPackagePopupStep(
        ImmutableList<CandidatePackage> rows, Consumer<CandidatePackage> onChosen) {
      super("Select package to add ", rows);
      this.onChosen = onChosen;
    }

    @Override
    public String getTextFor(CandidatePackage candidate) {
      return String.format(
          "%s (%s %s)",
          candidate.path,
          candidate.packageCount,
          StringUtil.pluralize("package", candidate.packageCount));
    }

    @Override
    public PopupStep<?> onChosen(CandidatePackage selectedValue, boolean finalChoice) {
      if (selectedValue == null) {
        return FINAL_CHOICE;
      }
      if (finalChoice) {
        onChosen.accept(selectedValue);
      }
      return FINAL_CHOICE;
    }
  }
}
