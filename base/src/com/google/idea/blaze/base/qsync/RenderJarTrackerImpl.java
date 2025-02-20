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
package com.google.idea.blaze.base.qsync;

import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.idea.blaze.base.bazel.BazelExitCode;
import com.google.idea.blaze.base.logging.utils.querysync.BuildDepsStatsScope;
import com.google.idea.blaze.base.qsync.ArtifactTracker.UpdateResult;
import com.google.idea.blaze.base.scope.BlazeContext;
import com.google.idea.blaze.common.Label;
import com.google.idea.blaze.common.PrintOutput;
import com.google.idea.blaze.exception.BuildException;
import com.google.idea.blaze.qsync.BlazeProject;
import com.google.idea.blaze.qsync.project.BlazeProjectSnapshot;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * An implementation of {@link RenderJarTracker} service responsible for management of artifacts
 * used by rendering and layout preview surfaces.
 */
public class RenderJarTrackerImpl implements RenderJarTracker {

  private final BlazeProject blazeProject;
  private final RenderJarBuilder renderJarBuilder;
  private final ArtifactTracker artifactTracker;

  public RenderJarTrackerImpl(
      BlazeProject blazeProject,
      RenderJarBuilder renderJarBuilder,
      ArtifactTracker artifactTracker) {
    this.blazeProject = blazeProject;
    this.renderJarBuilder = renderJarBuilder;
    this.artifactTracker = artifactTracker;
  }

  /** Builds the render jars of the given files and adds then to the cache */
  @Override
  public void buildRenderJarForFile(BlazeContext context, List<Path> workspaceRelativePaths)
      throws IOException, BuildException {
    workspaceRelativePaths.forEach(path -> Preconditions.checkState(!path.isAbsolute(), path));

    BlazeProjectSnapshot snapshot =
        blazeProject
            .getCurrent()
            .orElseThrow(() -> new IllegalStateException("Failed to get the snapshot"));

    Set<Label> targets = new HashSet<>();
    Set<Label> buildTargets = new HashSet<>();
    for (Path workspaceRelativePath : workspaceRelativePaths) {
      Label targetOwner = snapshot.getTargetOwner(workspaceRelativePath);
      if (targetOwner != null) {
        buildTargets.add(targetOwner);
      } else {
        context.output(
            PrintOutput.error(
                "File %s does not seem to be part of a build rule that the IDE supports.",
                workspaceRelativePath));
        context.output(
            PrintOutput.error(
                "If this is a newly added supported rule, please re-sync your project."));
        context.setHasError();
        return;
      }
    }
    BuildDepsStatsScope.fromContext(context)
        .ifPresent(stats -> stats.setRequestedTargets(targets).setBuildTargets(buildTargets));
    RenderJarInfo renderJarInfo = renderJarBuilder.buildRenderJar(context, buildTargets);

    if (renderJarInfo.isEmpty()) {
      throw new NoRenderJarBuiltException(
          "Build produced no usable render jars. Please fix any build errors and retry.");
    }

    if (renderJarInfo.getExitCode() != BazelExitCode.SUCCESS) {
      // This will happen if there is an error in a build file, as no build actions are attempted
      // in that case.
      context.setHasWarnings();
      context.output(PrintOutput.error("There were build errors when generating render jar."));
    }

    Stopwatch stopwatch = Stopwatch.createStarted();
    UpdateResult updateResult = artifactTracker.update(targets, renderJarInfo, context);
    context.output(
        PrintOutput.log(
            String.format(
                "Updated cache in %d ms: updated %d artifacts, removed %d artifacts",
                stopwatch.elapsed().toMillis(),
                updateResult.updatedFiles().size(),
                updateResult.removedKeys().size())));
    if (updateResult.updatedFiles().isEmpty()) {
      context.output(
          PrintOutput.log(
              "Render jar already generated for files: "
                  + workspaceRelativePaths
                  + " and targets: "
                  + buildTargets));
    } else {
      context.output(
          PrintOutput.log(
              "Generated Render jars: "
                  + updateResult.updatedFiles()
                  + ", for these files: "
                  + workspaceRelativePaths
                  + ", and targets: "
                  + buildTargets));
    }
  }
}
