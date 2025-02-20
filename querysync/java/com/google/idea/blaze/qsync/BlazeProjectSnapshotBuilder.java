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
package com.google.idea.blaze.qsync;

import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.idea.blaze.common.Context;
import com.google.idea.blaze.exception.BuildException;
import com.google.idea.blaze.qsync.project.BlazeProjectSnapshot;
import com.google.idea.blaze.qsync.project.BuildGraphData;
import com.google.idea.blaze.qsync.project.PostQuerySyncData;
import com.google.idea.blaze.qsync.project.ProjectProto;
import com.google.idea.blaze.qsync.project.ProjectProto.Project;
import com.google.idea.blaze.qsync.query.QuerySummary;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Project refresher creates an appropriate {@link RefreshOperation} based on the project and
 * current VCS state.
 */
public class BlazeProjectSnapshotBuilder {

  private final ListeningExecutorService executor;
  private final PackageReader workspaceRelativePackageReader;
  private final Path workspaceRoot;
  private final ImmutableSet<String> handledRuleKinds;
  private final Supplier<Boolean> ccEnabledFlag;

  public BlazeProjectSnapshotBuilder(
      ListeningExecutorService executor,
      PackageReader workspaceRelativePackageReader,
      Path workspaceRoot,
      ImmutableSet<String> handledRuleKinds,
      Supplier<Boolean> ccEnabledFlag) {
    this.executor = executor;
    this.workspaceRelativePackageReader = workspaceRelativePackageReader;
    this.workspaceRoot = workspaceRoot;
    this.handledRuleKinds = handledRuleKinds;
    this.ccEnabledFlag = ccEnabledFlag;
  }

  /** {@code Function<ProjectProto.Project, ProjectProto.Project>} that can throw exceptions. */
  @FunctionalInterface
  public interface ProjectProtoTransform {
    ProjectProto.Project apply(ProjectProto.Project proto) throws BuildException;
  }

  /**
   * Creates a {@link BlazeProjectSnapshot}, which includes an expected IDE project structure, from
   * the {@code postQuerySyncData} and a function {@code applyBuiltDependenciesTransform} that
   * applies transformations required to account for any currently synced(i.e. built) dependencies.
   */
  public BlazeProjectSnapshot createBlazeProjectSnapshot(
      Context<?> context,
      PostQuerySyncData postQuerySyncData,
      ProjectProtoTransform applyBuiltDependenciesTransform)
      throws BuildException {
    Path effectiveWorkspaceRoot =
        postQuerySyncData.vcsState().flatMap(s -> s.workspaceSnapshotPath).orElse(workspaceRoot);
    WorkspaceResolvingPackageReader packageReader =
        new WorkspaceResolvingPackageReader(effectiveWorkspaceRoot, workspaceRelativePackageReader);
    GraphToProjectConverter graphToProjectConverter =
        new GraphToProjectConverter(
            packageReader,
            effectiveWorkspaceRoot,
            context,
            postQuerySyncData.projectDefinition(),
            executor);
    QuerySummary querySummary = postQuerySyncData.querySummary();
    BuildGraphData graph =
        new BlazeQueryParser(querySummary, context, handledRuleKinds, ccEnabledFlag).parse();
    Project project = null;
    try {
      project = applyBuiltDependenciesTransform.apply(graphToProjectConverter.createProject(graph));
    } catch (Exception e) {
      throw new BuildException(e);
    }
    return BlazeProjectSnapshot.builder()
        .queryData(postQuerySyncData)
        .graph(graph)
        .project(project)
        .build();
  }
}
