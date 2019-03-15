/*
 * Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.idea.blaze.python.sync;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.devtools.intellij.ideinfo.IntellijIdeInfo.PyIdeInfo.PythonVersion;
import com.google.idea.blaze.base.ideinfo.PyIdeInfo;
import com.google.idea.blaze.base.ideinfo.TargetIdeInfo;
import com.google.idea.blaze.base.io.VfsUtils;
import com.google.idea.blaze.base.model.BlazeProjectData;
import com.google.idea.blaze.base.model.BlazeVersionData;
import com.google.idea.blaze.base.model.primitives.LanguageClass;
import com.google.idea.blaze.base.model.primitives.WorkspaceRoot;
import com.google.idea.blaze.base.model.primitives.WorkspaceType;
import com.google.idea.blaze.base.projectview.ProjectView;
import com.google.idea.blaze.base.projectview.ProjectViewEdit;
import com.google.idea.blaze.base.projectview.ProjectViewSet;
import com.google.idea.blaze.base.projectview.ProjectViewSet.ProjectViewFile;
import com.google.idea.blaze.base.projectview.section.ListSection;
import com.google.idea.blaze.base.projectview.section.ScalarSection;
import com.google.idea.blaze.base.projectview.section.sections.AdditionalLanguagesSection;
import com.google.idea.blaze.base.projectview.section.sections.WorkspaceTypeSection;
import com.google.idea.blaze.base.scope.BlazeContext;
import com.google.idea.blaze.base.scope.output.IssueOutput;
import com.google.idea.blaze.base.sync.BlazeSyncManager;
import com.google.idea.blaze.base.sync.BlazeSyncParams;
import com.google.idea.blaze.base.sync.BlazeSyncPlugin;
import com.google.idea.blaze.base.sync.GenericSourceFolderProvider;
import com.google.idea.blaze.base.sync.RefreshRequestType;
import com.google.idea.blaze.base.sync.SourceFolderProvider;
import com.google.idea.blaze.base.sync.SyncMode;
import com.google.idea.blaze.base.sync.projectview.WorkspaceLanguageSettings;
import com.google.idea.common.experiments.BoolExperiment;
import com.google.idea.common.transactions.Transactions;
import com.intellij.facet.Facet;
import com.intellij.facet.FacetManager;
import com.intellij.facet.ModifiableFacetModel;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.projectRoots.impl.SdkConfigurationUtil;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.pom.NavigatableAdapter;
import com.intellij.util.PlatformUtils;
import com.jetbrains.python.PythonModuleTypeBase;
import com.jetbrains.python.facet.LibraryContributingFacet;
import com.jetbrains.python.facet.PythonFacetSettings;
import com.jetbrains.python.sdk.PythonSdkType;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;

/** Allows people to use a python workspace. */
public class BlazePythonSyncPlugin implements BlazeSyncPlugin {

  private static final ImmutableMap<PythonVersion, String> BASE_SDKS =
      ImmutableMap.of(
          PythonVersion.PY2, "/usr/bin/python2",
          PythonVersion.PY3, "/usr/bin/python3");

  private static final BoolExperiment refreshExecRoot =
      new BoolExperiment("refresh.exec.root.python", true);

  @Override
  public Set<LanguageClass> getSupportedLanguagesInWorkspace(WorkspaceType workspaceType) {
    // supported for legacy reasons, but otherwise ignored.
    return ImmutableSet.of(LanguageClass.PYTHON);
  }

  @Override
  public Set<LanguageClass> getAlwaysActiveLanguages() {
    return ImmutableSet.of(LanguageClass.PYTHON);
  }

  @Nullable
  @Override
  public WorkspaceType getDefaultWorkspaceType() {
    return PlatformUtils.isPyCharm() ? WorkspaceType.PYTHON : null;
  }

  @Nullable
  @Override
  public ModuleType getWorkspaceModuleType(WorkspaceType workspaceType) {
    // left here for backwards compatibility -- python workspace types are deprecated.
    if (workspaceType == WorkspaceType.PYTHON && supportsPythonWorkspaceType()) {
      return PythonModuleTypeBase.getInstance();
    }
    return null;
  }

  private static boolean supportsPythonWorkspaceType() {
    // support python workspace type in IntelliJ for historical reasons (this is discouraged for new
    // projects)
    return PlatformUtils.isIntelliJ() || PlatformUtils.isPyCharm();
  }

  @Override
  public ImmutableList<WorkspaceType> getSupportedWorkspaceTypes() {
    // 'supports' python by giving a more detailed error message with quick-fix later in the sync
    // process, in the case where a python workspace type is not supported.
    return ImmutableList.of(WorkspaceType.PYTHON);
  }

  @Nullable
  @Override
  public SourceFolderProvider getSourceFolderProvider(BlazeProjectData projectData) {
    if (!projectData.getWorkspaceLanguageSettings().isWorkspaceType(WorkspaceType.PYTHON)) {
      return null;
    }
    return GenericSourceFolderProvider.INSTANCE;
  }

  @Override
  public void updateProjectStructure(
      Project project,
      BlazeContext context,
      WorkspaceRoot workspaceRoot,
      ProjectViewSet projectViewSet,
      BlazeProjectData blazeProjectData,
      @Nullable BlazeProjectData oldBlazeProjectData,
      ModuleEditor moduleEditor,
      Module workspaceModule,
      ModifiableRootModel workspaceModifiableModel,
      BlazeSyncParams syncParams) {
    if (syncParams.syncMode == SyncMode.NO_BUILD) {
      return;
    }
    updatePythonFacet(
        project, context, blazeProjectData, workspaceModule, workspaceModifiableModel);
  }

  @Override
  public ImmutableSetMultimap<RefreshRequestType, VirtualFile> filesToRefresh(
      BlazeProjectData blazeProjectData) {
    if (!blazeProjectData.getWorkspaceLanguageSettings().isLanguageActive(LanguageClass.PYTHON)) {
      return ImmutableSetMultimap.of();
    }
    if (!refreshExecRoot.getValue()) {
      return ImmutableSetMultimap.of();
    }
    // recursive refresh of the blaze execution root. This is required because our blaze aspect
    // can't yet tell us exactly which genfiles are required to resolve the project.
    VirtualFile execRoot =
        VfsUtils.resolveVirtualFile(blazeProjectData.getBlazeInfo().getExecutionRoot());
    if (execRoot == null) {
      return ImmutableSetMultimap.of();
    }
    return ImmutableSetMultimap.of(
        RefreshRequestType.create(/* recursive= */ true, /* reloadChildren= */ true), execRoot);
  }

  private static void updatePythonFacet(
      Project project,
      BlazeContext context,
      BlazeProjectData blazeProjectData,
      Module workspaceModule,
      ModifiableRootModel workspaceModifiableModel) {
    if (!PythonFacetUtil.usePythonFacets()) {
      return;
    }
    if (!blazeProjectData.getWorkspaceLanguageSettings().isLanguageActive(LanguageClass.PYTHON)
        || blazeProjectData.getWorkspaceLanguageSettings().isWorkspaceType(WorkspaceType.PYTHON)) {
      removeFacet(workspaceModule);
      return;
    }
    if (ModuleType.get(workspaceModule) instanceof PythonModuleTypeBase) {
      return;
    }
    LibraryContributingFacet<?> pythonFacet =
        getOrCreatePythonFacet(project, context, workspaceModule, blazeProjectData);
    if (pythonFacet == null) {
      return;
    }
    Library pythonLib = getFacetLibrary(pythonFacet);
    if (pythonLib != null) {
      workspaceModifiableModel.addLibraryEntry(pythonLib);
    }
  }

  private static void removeFacet(Module workspaceModule) {
    FacetManager manager = FacetManager.getInstance(workspaceModule);
    ModifiableFacetModel facetModel = manager.createModifiableModel();
    LibraryContributingFacet<?> facet = manager.findFacet(PythonFacetUtil.getFacetId(), "Python");
    if (facet != null) {
      facetModel.removeFacet(facet);
      facetModel.commit();
    }
  }

  @Nullable
  private static LibraryContributingFacet<?> getOrCreatePythonFacet(
      Project project, BlazeContext context, Module module, BlazeProjectData blazeProjectData) {
    LibraryContributingFacet<?> facet = findPythonFacet(module);
    if (facet != null && isValidPythonSdk(PythonFacetUtil.getSdk(facet))) {
      return facet;
    }
    FacetManager manager = FacetManager.getInstance(module);
    ModifiableFacetModel facetModel = manager.createModifiableModel();
    if (facet != null) {
      // we can't modify in place, IntelliJ has no hook to trigger change events. Instead we create
      // a new facet.
      facetModel.removeFacet(facet);
    }

    Sdk sdk = getOrCreatePythonSdk(project, blazeProjectData, context);
    if (sdk == null) {
      String fixDirections =
          "Configure a suitable Python Interpreter for the module \""
              + module.getName()
              + "\" via File->\"Project Structure...\", \"Project Settings\"->\"Facets\", then"
              + " sync the project again.";
      if (PlatformUtils.isCLion()) {
        fixDirections =
            "Configure a suitable Python Interpreter for the module \""
                + module.getName()
                + "\" via File->\"Settings...\", \"Build,"
                + " Execution, Deployment\"->\"Python Interpreter\", then sync the project again.";
      }
      String msg = "Unable to find a compatible Python SDK installed.\n" + fixDirections;
      IssueOutput.error(msg).submit(context);
      return null;
    }

    facet = manager.createFacet(PythonFacetUtil.getFacetType(), "Python", null);
    // This is ugly like this to get around PythonFacet related classes being in different package
    // paths in different IDEs. Thankfully, PythonFacetSettings is in the same packackage path.
    if (facet.getConfiguration() instanceof PythonFacetSettings) {
      ((PythonFacetSettings) facet.getConfiguration()).setSdk(sdk);
    }
    facetModel.addFacet(facet);
    facetModel.commit();
    return facet;
  }

  private static boolean isValidPythonSdk(@Nullable Sdk sdk) {
    if (sdk == null) {
      return false;
    }

    if (!(sdk.getSdkType() instanceof PythonSdkType)) {
      return false;
    }
    // facets aren't properly updated when SDKs change (e.g. when they're deleted), so we need to
    // manually check against the full list.
    if (!PythonSdkType.getAllSdks().contains(sdk)) {
      return false;
    }

    // If an SDK exists with a home path that doesn't exist anymore, it's not valid
    String sdkHome = sdk.getHomePath();
    if (sdkHome == null) {
      return false;
    }
    File sdkHomeFile = new File(sdkHome);
    if (!sdkHomeFile.exists()) {
      return false;
    }

    // Allow PySdkSuggester extensions the ability to veto an SDK as deprecated
    return !isDeprecatedPythonSdk(sdk);
  }

  @Nullable
  private static Library getFacetLibrary(LibraryContributingFacet<?> facet) {
    Sdk sdk = PythonFacetUtil.getSdk(facet);
    if (sdk == null) {
      return null;
    }
    return LibraryTablesRegistrar.getInstance()
        .getLibraryTable()
        .getLibraryByName(
            sdk.getName() + LibraryContributingFacet.PYTHON_FACET_LIBRARY_NAME_SUFFIX);
  }

  private static LibraryContributingFacet<?> findPythonFacet(Module module) {
    final Facet<?>[] allFacets = FacetManager.getInstance(module).getAllFacets();
    for (Facet<?> facet : allFacets) {
      if ((facet instanceof LibraryContributingFacet)
          && (facet.getTypeId() == PythonFacetUtil.getFacetId())) {
        return (LibraryContributingFacet<?>) facet;
      }
    }
    return null;
  }

  @Override
  public void updateProjectSdk(
      Project project,
      BlazeContext context,
      ProjectViewSet projectViewSet,
      BlazeVersionData blazeVersionData,
      BlazeProjectData blazeProjectData) {
    if (!blazeProjectData.getWorkspaceLanguageSettings().isWorkspaceType(WorkspaceType.PYTHON)) {
      return;
    }
    Sdk currentSdk = ProjectRootManager.getInstance(project).getProjectSdk();

    if (isValidPythonSdk(currentSdk)) {
      return;
    }
    Sdk sdk = getOrCreatePythonSdk(project, blazeProjectData, context);
    if (sdk != null) {
      setProjectSdk(project, sdk);
    }
  }

  private static boolean isDeprecatedPythonSdk(Sdk sdk) {
    return Arrays.stream(PySdkSuggester.EP_NAME.getExtensions())
        .anyMatch(s -> s.isDeprecatedSdk(sdk));
  }

  /**
   * Returns any Python SDK it finds, preferring Python 3 over Python 2.
   *
   * @param project the project the SDK is for.
   * @return a Python SDK.
   */
  @Nullable
  private static Sdk findAnyPythonSdk(Project project) {
    return findCompatibleSdk(
        project,
        ImmutableSet.of(PythonVersion.PY3, PythonVersion.PY2),
        ImmutableList.of(PythonVersion.PY3, PythonVersion.PY2));
  }

  /**
   * Tries to find the first SDK on the 'compatible' list that exists on the local system and also
   * is present in the 'configured' set.
   *
   * @param project the project the SDK is for.
   * @param configured a set of python versions that targets in the project are configured for.
   * @param compatible a set of python versions that are compatible with the targets in the project.
   * @return a Python SDK
   */
  @VisibleForTesting
  @Nullable
  private static Sdk findCompatibleSdk(
      Project project, Set<PythonVersion> configured, List<PythonVersion> compatible) {
    for (PythonVersion version : compatible) {
      if (!configured.contains(version)) {
        continue;
      }
      for (PySdkSuggester suggester : PySdkSuggester.EP_NAME.getExtensions()) {
        Sdk s = suggester.suggestSdk(project, version);
        if (s != null) {
          return s;
        }
      }
      if (BASE_SDKS.containsKey(version)) {
        Sdk s = PySdkSuggester.findPythonSdk(BASE_SDKS.get(version));
        if (s != null) {
          return s;
        }
      }
    }
    return null;
  }

  static void populateCompatibility(
      BlazeProjectData blazeProjectData,
      Set<PythonVersion> configuredVersions,
      List<PythonVersion> compatibleVersions) {
    // compatibleVersions is an ordered list of python versions that will be reduced as target
    // constraints are processed. Earlier entries have priority.
    compatibleVersions.add(PythonVersion.PY3);
    compatibleVersions.add(PythonVersion.PY2);

    for (TargetIdeInfo ideInfo : blazeProjectData.getTargetMap().targets()) {
      PyIdeInfo pyIdeInfo = ideInfo.getPyIdeInfo();
      if (pyIdeInfo == null) {
        continue;
      }
      if (pyIdeInfo.getPythonVersion() != PythonVersion.UNKNOWN) {
        configuredVersions.add(pyIdeInfo.getPythonVersion());
      }

      switch (pyIdeInfo.getSrcsVersion()) {
        case SRC_PY2:
        case SRC_PY2ONLY:
          compatibleVersions.removeIf(version -> version != PythonVersion.PY2);
          break;
        case SRC_PY3:
        case SRC_PY3ONLY:
          compatibleVersions.removeIf(version -> version != PythonVersion.PY3);
          break;
        default:
          break;
      }
    }
  }

  @Nullable
  private static Sdk getOrCreatePythonSdk(
      Project project, BlazeProjectData blazeProjectData, BlazeContext context) {
    Set<PythonVersion> configuredVersions = new HashSet<>();
    List<PythonVersion> compatibleVersions = new ArrayList<>();

    createSDKsIfNeeded();

    populateCompatibility(blazeProjectData, configuredVersions, compatibleVersions);

    if (configuredVersions.isEmpty()) {
      return findAnyPythonSdk(project);
    }

    Sdk sdk = findCompatibleSdk(project, configuredVersions, compatibleVersions);

    if (sdk == null) {
      IssueOutput.warn("Could not find a python SDK compatible with all targets.").submit(context);
      Sdk anySdk = findAnyPythonSdk(project);
      if (anySdk != null) {
        IssueOutput.warn("Using: " + anySdk.getVersionString()).submit(context);
      }
      return anySdk;
    }

    if (configuredVersions.size() > 1) {
      IssueOutput.warn("Targets configured for different python versions.").submit(context);
      IssueOutput.warn("Using: " + sdk.getVersionString()).submit(context);
    }
    return sdk;
  }

  private static void setProjectSdk(Project project, Sdk sdk) {
    Transactions.submitWriteActionTransactionAndWait(
        () -> ProjectRootManager.getInstance(project).setProjectSdk(sdk));
  }

  private static void createSDKsIfNeeded() {
    for (PySdkSuggester suggester : PySdkSuggester.EP_NAME.getExtensions()) {
      suggester.syncSdks();
    }

    for (String homePath : BASE_SDKS.values()) {
      if (!new File(homePath).exists()) {
        continue;
      }
      Sdk sdk = PySdkSuggester.findPythonSdk(homePath);
      if (sdk != null) {
        continue;
      }
      SdkConfigurationUtil.createAndAddSDK(homePath, PythonSdkType.getInstance());
    }
  }

  @Override
  public boolean validateProjectView(
      @Nullable Project project,
      BlazeContext context,
      ProjectViewSet projectViewSet,
      WorkspaceLanguageSettings workspaceLanguageSettings) {
    ProjectViewFile topLevelProjectViewFile = projectViewSet.getTopLevelProjectViewFile();
    if (!supportsPythonWorkspaceType()
        && workspaceLanguageSettings.isWorkspaceType(WorkspaceType.PYTHON)) {
      String msg = "Python workspace type is not supported (and is unnecessary) for this IDE. ";
      boolean fixable =
          topLevelProjectViewFile != null
              && topLevelProjectViewFile.projectView.getScalarValue(WorkspaceTypeSection.KEY)
                  == WorkspaceType.PYTHON;
      msg +=
          fixable
              ? "Click here to remove it, retaining python support"
              : "Please remove it and resync.";
      IssueOutput.error(msg)
          .navigatable(
              project == null || !fixable
                  ? null
                  : new NavigatableAdapter() {
                    @Override
                    public void navigate(boolean requestFocus) {
                      fixLanguageSupport(project, true);
                    }
                  })
          .submit(context);
      return false;
    }
    return true;
  }

  private static void fixLanguageSupport(Project project, boolean removeWorkspaceType) {
    ProjectViewEdit edit =
        ProjectViewEdit.editLocalProjectView(
            project,
            builder -> {
              if (removeWorkspaceType) {
                removePythonWorkspaceType(builder);
              }
              removeFromAdditionalLanguages(builder);
              return true;
            });
    if (edit == null) {
      Messages.showErrorDialog(
          "Could not modify project view. Check for errors in your project view and try again",
          "Error");
      return;
    }
    edit.apply();
    BlazeSyncManager.getInstance(project).incrementalProjectSync();
  }

  private static void removePythonWorkspaceType(ProjectView.Builder builder) {
    ScalarSection<WorkspaceType> section = builder.getLast(WorkspaceTypeSection.KEY);
    if (section != null && section.getValue() == WorkspaceType.PYTHON) {
      builder.remove(section);
    }
  }

  private static void removeFromAdditionalLanguages(ProjectView.Builder builder) {
    ListSection<LanguageClass> existingSection = builder.getLast(AdditionalLanguagesSection.KEY);
    builder.replace(
        existingSection,
        ListSection.update(AdditionalLanguagesSection.KEY, existingSection)
            .remove(LanguageClass.PYTHON));
  }
}
