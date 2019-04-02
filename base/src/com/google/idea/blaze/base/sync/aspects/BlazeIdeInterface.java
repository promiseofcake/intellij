/*
 * Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.idea.blaze.base.sync.aspects;

import com.google.common.collect.ImmutableListMultimap;
import com.google.idea.blaze.base.command.buildresult.OutputArtifact;
import com.google.idea.blaze.base.command.info.BlazeInfo;
import com.google.idea.blaze.base.ideinfo.TargetMap;
import com.google.idea.blaze.base.model.BlazeProjectData;
import com.google.idea.blaze.base.model.BlazeVersionData;
import com.google.idea.blaze.base.model.SyncState;
import com.google.idea.blaze.base.model.primitives.WorkspaceRoot;
import com.google.idea.blaze.base.projectview.ProjectViewSet;
import com.google.idea.blaze.base.scope.BlazeContext;
import com.google.idea.blaze.base.sync.BlazeSyncBuildResult;
import com.google.idea.blaze.base.sync.projectview.WorkspaceLanguageSettings;
import com.google.idea.blaze.base.sync.sharding.ShardedTargetList;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import javax.annotation.Nullable;

/** A blaze build interface used for mocking out the blaze layer in tests. */
public interface BlazeIdeInterface {

  static BlazeIdeInterface getInstance() {
    return ServiceManager.getService(BlazeIdeInterface.class);
  }

  /** The result of the blaze build sync step. */
  class BlazeBuildOutputs {
    /** All output artifacts from the blaze build step. */
    public final ImmutableListMultimap<String, OutputArtifact> perOutputGroupArtifacts;

    public final BuildResult buildResult;

    public BlazeBuildOutputs(
        ImmutableListMultimap<String, OutputArtifact> perOutputGroupArtifacts,
        BuildResult buildResult) {
      this.perOutputGroupArtifacts = perOutputGroupArtifacts;
      this.buildResult = buildResult;
    }
  }

  /**
   * Parses the output intellij-info.txt files, updating the project's {@link TargetMap} and
   * BlazeIdeInterfaceState accordingly.
   *
   * @param mergeWithOldState If true, we overlay the given targets to the current rule map.
   */
  @Nullable
  TargetMap updateTargetMap(
      Project project,
      BlazeContext context,
      WorkspaceRoot workspaceRoot,
      BlazeSyncBuildResult buildResult,
      SyncState.Builder syncStateBuilder,
      boolean mergeWithOldState,
      @Nullable BlazeProjectData oldProjectData);

  /**
   * The blaze build phase of sync.
   *
   * <p>Builds the 'ide-info-*' and 'ide-resolve-*' output groups.
   */
  BlazeBuildOutputs buildIdeArtifacts(
      Project project,
      BlazeContext context,
      WorkspaceRoot workspaceRoot,
      ProjectViewSet projectViewSet,
      BlazeInfo blazeInfo,
      ShardedTargetList shardedTargets,
      WorkspaceLanguageSettings workspaceLanguageSettings);

  /**
   * Attempts to compile the requested ide artifacts.
   *
   * <p>Amounts to a build of the ide-compile output group.
   */
  BuildResult compileIdeArtifacts(
      Project project,
      BlazeContext context,
      WorkspaceRoot workspaceRoot,
      ProjectViewSet projectViewSet,
      BlazeVersionData blazeVersionData,
      WorkspaceLanguageSettings workspaceLanguageSettings,
      ShardedTargetList shardedTargets);
}
