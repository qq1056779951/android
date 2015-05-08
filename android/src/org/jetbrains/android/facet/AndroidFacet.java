/*
 * Copyright 2000-2010 JetBrains s.r.o.
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
package org.jetbrains.android.facet;

import com.android.annotations.NonNull;
import com.android.builder.model.*;
import com.android.prefs.AndroidLocation;
import com.android.sdklib.AndroidVersion;
import com.android.sdklib.IAndroidTarget;
import com.android.sdklib.internal.avd.AvdInfo;
import com.android.sdklib.internal.avd.AvdManager;
import com.android.tools.idea.avdmanager.EmulatorRunner;
import com.android.tools.idea.configurations.ConfigurationManager;
import com.android.tools.idea.databinding.DataBindingUtil;
import com.android.tools.idea.gradle.GradleSyncState;
import com.android.tools.idea.gradle.IdeaAndroidProject;
import com.android.tools.idea.gradle.project.GradleSyncListener;
import com.android.tools.idea.gradle.util.Projects;
import com.android.tools.idea.model.AndroidModuleInfo;
import com.android.tools.idea.rendering.*;
import com.android.tools.idea.run.LaunchCompatibility;
import com.android.tools.idea.sdk.IdeSdks;
import com.android.tools.idea.templates.TemplateManager;
import com.android.utils.ILogger;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.intellij.CommonBundle;
import com.intellij.ProjectTopics;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.ParametersList;
import com.intellij.facet.Facet;
import com.intellij.facet.FacetManager;
import com.intellij.facet.FacetTypeId;
import com.intellij.facet.FacetTypeRegistry;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.projectRoots.SdkModificator;
import com.intellij.openapi.roots.*;
import com.intellij.openapi.startup.StartupManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.JarFileSystem;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.impl.file.PsiPackageImpl;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.ProjectScope;
import com.intellij.psi.search.searches.ClassInheritorsSearch;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.util.Processor;
import com.intellij.util.ThreeState;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.xml.ConvertContext;
import com.intellij.util.xml.DomElement;
import org.gradle.tooling.model.UnsupportedMethodException;
import org.jetbrains.android.compiler.AndroidAutogeneratorMode;
import org.jetbrains.android.dom.manifest.Manifest;
import org.jetbrains.android.resourceManagers.LocalResourceManager;
import org.jetbrains.android.resourceManagers.ResourceManager;
import org.jetbrains.android.resourceManagers.SystemResourceManager;
import org.jetbrains.android.sdk.*;
import org.jetbrains.android.util.AndroidBundle;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jps.android.model.impl.JpsAndroidModuleProperties;

import java.io.File;
import java.util.*;

import static com.android.SdkConstants.ANDROID_MANIFEST_XML;
import static com.android.SdkConstants.FN_EMULATOR;
import static com.android.tools.idea.AndroidPsiUtils.getModuleSafely;
import static com.android.tools.idea.startup.AndroidStudioSpecificInitializer.isAndroidStudio;
import static com.intellij.openapi.util.io.FileUtil.toSystemIndependentName;
import static com.intellij.openapi.vfs.JarFileSystem.JAR_SEPARATOR;
import static com.intellij.openapi.vfs.VfsUtilCore.virtualToIoFile;
import static com.intellij.util.ArrayUtilRt.find;
import static org.jetbrains.android.compiler.AndroidCompileUtil.generate;
import static org.jetbrains.android.facet.AndroidRootUtil.*;
import static org.jetbrains.android.sdk.AndroidSdkUtils.isAndroidSdk;
import static org.jetbrains.android.util.AndroidCommonUtils.ANNOTATIONS_JAR_RELATIVE_PATH;
import static org.jetbrains.android.util.AndroidCommonUtils.toolPath;
import static org.jetbrains.android.util.AndroidUtils.SYSTEM_RESOURCE_PACKAGE;
import static org.jetbrains.android.util.AndroidUtils.loadDomElement;

/**
 * @author yole
 */
public class AndroidFacet extends Facet<AndroidFacetConfiguration> {
  private static final Logger LOG = Logger.getInstance("#org.jetbrains.android.facet.AndroidFacet");

  public static final FacetTypeId<AndroidFacet> ID = new FacetTypeId<AndroidFacet>("android");
  public static final String NAME = "Android";

  private static final Object APP_RESOURCES_LOCK = new Object();
  private static final Object PROJECT_RESOURCES_LOCK = new Object();
  private static final Object MODULE_RESOURCES_LOCK = new Object();
  private static boolean ourDynamicTemplateMenuCreated;

  private AvdManager myAvdManager = null;
  private AndroidSdkData mySdkData;
  /**
   * A cache for in memory {@linkplain PsiPackage}s for data binding.
   * <p>
   * To avoid any project leaks, it is cleared when {@link #setIdeaAndroidProject(IdeaAndroidProject)} is called.
   */
  private Map<String, PsiPackage> myDataBindingPsiPackages = Maps.newConcurrentMap();

  private SystemResourceManager myPublicSystemResourceManager;
  private SystemResourceManager myFullSystemResourceManager;
  private LocalResourceManager myLocalResourceManager;

  private final Map<String, Map<String, SmartPsiElementPointer<PsiClass>>> myInitialClassMaps = Maps.newHashMap();

  private PsiClass myLightRClass;

  private Map<String, CachedValue<Map<String, PsiClass>>> myClassMaps = Maps.newHashMap();

  private final Object myClassMapLock = new Object();

  private final Set<AndroidAutogeneratorMode> myDirtyModes = EnumSet.noneOf(AndroidAutogeneratorMode.class);
  private final Map<AndroidAutogeneratorMode, Set<String>> myAutogeneratedFiles = Maps.newHashMap();

  private volatile boolean myAutogenerationEnabled = false;

  private ConfigurationManager myConfigurationManager;
  private LocalResourceRepository myModuleResources;
  private AppResourceRepository myAppResources;
  private ProjectResourceRepository myProjectResources;
  private IdeaAndroidProject myIdeaAndroidProject;
  private final ResourceFolderManager myFolderManager = new ResourceFolderManager(this);

  private SourceProvider myMainSourceSet;
  private IdeaSourceProvider myMainIdeaSourceSet;
  private final AndroidModuleInfo myAndroidModuleInfo = AndroidModuleInfo.create(this);
  private RenderService myRenderService;

  public AndroidFacet(@NotNull Module module, String name, @NotNull AndroidFacetConfiguration configuration) {
    //noinspection ConstantConditions
    super(getFacetType(), module, name, configuration, null);
    configuration.setFacet(this);
    DataBindingUtil.initFor(this);
  }

  public boolean isAutogenerationEnabled() {
    return myAutogenerationEnabled;
  }

  public boolean isGradleProject() {
    return !getProperties().ALLOW_USER_CONFIGURATION;
  }

  public boolean isLibraryProject() {
    return getProperties().LIBRARY_PROJECT;
  }

  public void setLibraryProject(boolean library) {
    getProperties().LIBRARY_PROJECT = library;
  }

  /**
   * Returns the main source provider for the project. For non-Gradle projects it returns a {@link SourceProvider} wrapper
   * which provides information about the old project.
   */
  @NotNull
  public SourceProvider getMainSourceProvider() {
    if (myIdeaAndroidProject != null) {
      return myIdeaAndroidProject.getDelegate().getDefaultConfig().getSourceProvider();
    } else {
      if (myMainSourceSet == null) {
        myMainSourceSet = new LegacySourceProvider();
      }
      return myMainSourceSet;
    }
  }

  @NotNull
  public IdeaSourceProvider getMainIdeaSourceProvider() {
    if (!isGradleProject()) {
      if (myMainIdeaSourceSet == null) {
        myMainIdeaSourceSet = IdeaSourceProvider.create(this);
      }
    } else {
      SourceProvider mainSourceSet = getMainSourceProvider();
      if (myMainIdeaSourceSet == null || mainSourceSet != myMainSourceSet) {
        myMainIdeaSourceSet = IdeaSourceProvider.create(mainSourceSet);
      }
    }

    return myMainIdeaSourceSet;
  }

  @NotNull
  public List<IdeaSourceProvider> getMainIdeaTestSourceProviders() {
    if (!isGradleProject() || myIdeaAndroidProject == null) {
      return Collections.emptyList();
    }

    Collection<SourceProviderContainer> extraSourceProviders =
      myIdeaAndroidProject.getDelegate().getDefaultConfig().getExtraSourceProviders();

    List<IdeaSourceProvider> providers = Lists.newArrayList();
    for (SourceProvider sourceProvider : myIdeaAndroidProject.getSourceProvidersForSelectedTestArtifact(extraSourceProviders)) {
      providers.add(IdeaSourceProvider.create(sourceProvider));
    }

    return providers;
  }

  /**
   * Returns the source provider for the current build type, which will never be null for a Gradle based
   * Android project, and always null for a legacy Android project
   *
   * @return the build type source set or null
   */
  @Nullable
  public SourceProvider getBuildTypeSourceProvider() {
    if (myIdeaAndroidProject != null) {
      Variant selectedVariant = myIdeaAndroidProject.getSelectedVariant();
      BuildTypeContainer buildType = myIdeaAndroidProject.findBuildType(selectedVariant.getBuildType());
      assert buildType != null;
      return buildType.getSourceProvider();
    } else {
      return null;
    }
  }

  /**
   * Like {@link #getBuildTypeSourceProvider()} but typed for internal IntelliJ usage with
   * {@link VirtualFile} instead of {@link File} references
   *
   * @return the build type source set or null
   */
  @Nullable
  public IdeaSourceProvider getIdeaBuildTypeSourceProvider() {
    SourceProvider sourceProvider = getBuildTypeSourceProvider();
    if (sourceProvider != null) {
      return IdeaSourceProvider.create(sourceProvider);
    } else {
      return null;
    }
  }

  @NotNull
  public List<IdeaSourceProvider> getIdeaBuildTypeTestSourceProvider() {
    if (myIdeaAndroidProject == null) {
      return Collections.emptyList();
    }

    List<IdeaSourceProvider> providers = Lists.newArrayList();
    Variant selectedVariant = myIdeaAndroidProject.getSelectedVariant();
    BuildTypeContainer buildType = myIdeaAndroidProject.findBuildType(selectedVariant.getBuildType());
    assert buildType != null;

    Collection<SourceProvider> testSourceProviders =
      myIdeaAndroidProject.getSourceProvidersForSelectedTestArtifact(buildType.getExtraSourceProviders());


    for (SourceProvider sourceProvider : testSourceProviders) {
      providers.add(IdeaSourceProvider.create(sourceProvider));
    }

    return providers;
  }

  public ResourceFolderManager getResourceFolderManager() {
    return myFolderManager;
  }

  /**
   * Returns all resource directories, in the overlay order
   *
   * @return a list of all resource directories
   */
  @NotNull
  public List<VirtualFile> getAllResourceDirectories() {
    return myFolderManager.getFolders();
  }

  /**
   * @return the name of the build type
   */
  @Nullable
  public String getBuildTypeName() {
    return myIdeaAndroidProject != null ? myIdeaAndroidProject.getSelectedVariant().getName() : null;
  }

  /**
   * Returns the source providers for the available flavors, which will never be null for a Gradle based
   * Android project, and always null for a legacy Android project
   *
   * @return the flavor source providers or null in legacy projects
   */
  @Nullable
  public List<SourceProvider> getFlavorSourceProviders() {
    if (myIdeaAndroidProject != null) {
      Variant selectedVariant = myIdeaAndroidProject.getSelectedVariant();
      List<String> productFlavors = selectedVariant.getProductFlavors();
      List<SourceProvider> providers = Lists.newArrayList();
      for (String flavor : productFlavors) {
        ProductFlavorContainer productFlavor = myIdeaAndroidProject.findProductFlavor(flavor);
        assert productFlavor != null;
        providers.add(productFlavor.getSourceProvider());
      }
      return providers;
    } else {
      return null;
    }
  }

  /**
   * Like {@link #getFlavorSourceProviders()} but typed for internal IntelliJ usage with {@link VirtualFile} instead of {@link File}
   * references.
   *
   * @return the flavor source providers or {@code null} in legacy projects.
   */
  @Nullable
  public List<IdeaSourceProvider> getIdeaFlavorSourceProviders() {
    List<SourceProvider> sourceProviders = getFlavorSourceProviders();
    if (sourceProviders != null) {
      List<IdeaSourceProvider> ideaSourceProviders = Lists.newArrayListWithExpectedSize(sourceProviders.size());
      for (SourceProvider provider : sourceProviders) {
        ideaSourceProviders.add(IdeaSourceProvider.create(provider));
      }

      return ideaSourceProviders;
    } else {
      return null;
    }
  }

  @NotNull
  public List<IdeaSourceProvider> getIdeaFlavorTestSourceProviders() {
    if (myIdeaAndroidProject == null) {
      return Collections.emptyList();
    }

    Variant selectedVariant = myIdeaAndroidProject.getSelectedVariant();
    List<String> productFlavors = selectedVariant.getProductFlavors();
    List<IdeaSourceProvider> providers = Lists.newArrayList();
    for (String flavor : productFlavors) {
      ProductFlavorContainer productFlavor = myIdeaAndroidProject.findProductFlavor(flavor);
      assert productFlavor != null;

      Collection<SourceProvider> testSourceProviders =
        myIdeaAndroidProject.getSourceProvidersForSelectedTestArtifact(productFlavor.getExtraSourceProviders());

      for (SourceProvider sourceProvider : testSourceProviders) {
        providers.add(IdeaSourceProvider.create(sourceProvider));
      }
    }

    return providers;
  }

  /**
   * Returns the source provider specific to the flavor combination, if any.
   *
   * @return the source provider or {@code null}.
   */
  @Nullable
  public SourceProvider getMultiFlavorSourceProvider() {
    if (myIdeaAndroidProject != null) {
      Variant selectedVariant = myIdeaAndroidProject.getSelectedVariant();
      AndroidArtifact mainArtifact = selectedVariant.getMainArtifact();
      SourceProvider provider = mainArtifact.getMultiFlavorSourceProvider();
      if (provider != null) {
        return provider;
      }
    }

    return null;
  }

  /**
   * Like {@link #getMultiFlavorSourceProvider()} but typed for internal IntelliJ usage with {@link VirtualFile} instead of {@link File}
   * references.
   *
   * @return the flavor source providers or {@code null} in legacy projects.
   */
  @Nullable
  public IdeaSourceProvider getIdeaMultiFlavorSourceProvider() {
    SourceProvider provider = getMultiFlavorSourceProvider();
    if (provider != null) {
      return IdeaSourceProvider.create(provider);
    }

    return null;
  }

  /**
   * Returns the source provider specific to the variant, if any.
   *
   * @return the source provider or null
   */
  @Nullable
  public SourceProvider getVariantSourceProvider() {
    if (myIdeaAndroidProject != null) {
      Variant selectedVariant = myIdeaAndroidProject.getSelectedVariant();
      AndroidArtifact mainArtifact = selectedVariant.getMainArtifact();
      SourceProvider provider = mainArtifact.getVariantSourceProvider();
      if (provider != null) {
        return provider;
      }
    }

    return null;
  }

  /**
   * Like {@link #getVariantSourceProvider()} but typed for internal IntelliJ usage with {@link VirtualFile} instead of {@link File}
   * references.
   *
   * @return the flavor source providers or {@code null} in legacy projects.
   */
  @Nullable
  public IdeaSourceProvider getIdeaVariantSourceProvider() {
    SourceProvider provider = getVariantSourceProvider();
    if (provider != null) {
      return IdeaSourceProvider.create(provider);
    }

    return null;
  }

  /**
   * This returns the primary resource directory; the default location to place
   * newly created resources etc.  This method is marked deprecated since we should
   * be gradually adding in UI to allow users to choose specific resource folders
   * among the available flavors (see {@link #getFlavorSourceProviders()} etc).
   *
   * @return the primary resource dir, if any
   */
  @Deprecated
  @Nullable
  public VirtualFile getPrimaryResourceDir() {
    List<VirtualFile> dirs = getAllResourceDirectories();
    if (!dirs.isEmpty()) {
      return dirs.get(0);
    }

    return null;
  }

  public boolean isGeneratedFileRemoved(@NotNull AndroidAutogeneratorMode mode) {
    synchronized (myAutogeneratedFiles) {
      Set<String> filePaths = myAutogeneratedFiles.get(mode);

      if (filePaths != null) {
        for (String path : filePaths) {
          VirtualFile file = LocalFileSystem.getInstance().findFileByPath(path);

          if (file == null) {
            return true;
          }
        }
      }
    }
    return false;
  }

  public void clearAutogeneratedFiles(@NotNull AndroidAutogeneratorMode mode) {
    synchronized (myAutogeneratedFiles) {
      Set<String> set = myAutogeneratedFiles.get(mode);
      if (set != null) {
        set.clear();
      }
    }
  }

  public void markFileAutogenerated(@NotNull AndroidAutogeneratorMode mode, @NotNull VirtualFile file) {
    synchronized (myAutogeneratedFiles) {
      Set<String> set = myAutogeneratedFiles.get(mode);

      if (set == null) {
        set = Sets.newHashSet();
        myAutogeneratedFiles.put(mode, set);
      }
      set.add(file.getPath());
    }
  }

  @NotNull
  public Set<String> getAutogeneratedFiles(@NotNull AndroidAutogeneratorMode mode) {
    synchronized (myAutogeneratedFiles) {
      Set<String> set = myAutogeneratedFiles.get(mode);
      return set != null ? Sets.newHashSet(set) : Collections.<String>emptySet();
    }
  }

  private void activateSourceAutogenerating() {
    myAutogenerationEnabled = true;
  }

  public void androidPlatformChanged() {
    myAvdManager = null;
    myLocalResourceManager = null;
    myPublicSystemResourceManager = null;
    myInitialClassMaps.clear();
  }

  @NotNull
  public AvdInfo[] getAllAvds() {
    AvdManager manager = getAvdManagerSilently();
    if (manager != null) {
      if (reloadAvds(manager)) {
        return manager.getAllAvds();
      }
    }
    return new AvdInfo[0];
  }

  private boolean reloadAvds(AvdManager manager) {
    try {
      MessageBuildingSdkLog log = new MessageBuildingSdkLog();
      manager.reloadAvds(log);
      if (!log.getErrorMessage().isEmpty()) {
        Messages
          .showErrorDialog(getModule().getProject(), AndroidBundle.message("cant.load.avds.error.prefix") + ' ' + log.getErrorMessage(),
                           CommonBundle.getErrorTitle());
      }
      return true;
    }
    catch (AndroidLocation.AndroidLocationException e) {
      Messages.showErrorDialog(getModule().getProject(), AndroidBundle.message("cant.load.avds.error"), CommonBundle.getErrorTitle());
    }
    return false;
  }

  @NotNull
  public AvdInfo[] getValidCompatibleAvds() {
    AvdManager manager = getAvdManagerSilently();
    List<AvdInfo> result = Lists.newArrayList();
    if (manager != null && reloadAvds(manager)) {
      addCompatibleAvds(result, manager.getValidAvds());
    }
    return result.toArray(new AvdInfo[result.size()]);
  }

  @NotNull
  private AvdInfo[] addCompatibleAvds(@NotNull List<AvdInfo> to, @NotNull AvdInfo[] from) {
    AndroidVersion minSdk = AndroidModuleInfo.get(this).getRuntimeMinSdkVersion();
    AndroidPlatform platform = getConfiguration().getAndroidPlatform();
    if (platform == null) {
      LOG.error("Android Platform not set for module: " + getModule().getName());
      return new AvdInfo[0];
    }

    for (AvdInfo avd : from) {
      IAndroidTarget avdTarget = avd.getTarget();
      if (avdTarget == null || LaunchCompatibility.canRunOnAvd(minSdk, platform.getTarget(), avdTarget).isCompatible() != ThreeState.NO) {
        to.add(avd);
      }
    }
    return to.toArray(new AvdInfo[to.size()]);
  }

  @Nullable
  public AvdManager getAvdManagerSilently() {
    try {
      return getAvdManager(new AvdManagerLog());
    }
    catch (AvdsNotSupportedException ignored) {
    }
    catch (AndroidLocation.AndroidLocationException ignored) {
    }
    return null;
  }

  @NotNull
  public AvdManager getAvdManager(ILogger log) throws AvdsNotSupportedException, AndroidLocation.AndroidLocationException {
    if (myAvdManager == null) {
      AndroidSdkData sdkData = getSdkData();
      if (sdkData != null) {
        myAvdManager = AvdManager.getInstance(sdkData.getLocalSdk(), log);
      }
      else {
        throw new AvdsNotSupportedException();
      }
    }
    return myAvdManager;
  }

  @Nullable
  public AndroidSdkData getSdkData() {
    if (mySdkData == null) {
      AndroidPlatform platform = getConfiguration().getAndroidPlatform();
      mySdkData = platform != null ? platform.getSdkData() : null;
    }

    return mySdkData;
  }

  /**
   * Returns a target from a hash that was generated by {@link IAndroidTarget#hashString()}.
   *
   * @param hash the {@link IAndroidTarget} hash string.
   * @return The matching {@link IAndroidTarget} or {@code null}.
   */
  @Nullable
  public IAndroidTarget getTargetFromHashString(@NotNull String hash) {
    AndroidSdkData sdkData = getSdkData();
    return sdkData != null ? sdkData.getLocalSdk().getTargetFromHashString(hash) : null;
  }

  public void launchEmulator(@Nullable String avdName, @NotNull String commands) {
    File sdkLocation = null;
    if (Projects.isGradleProject(getModule().getProject()) && isAndroidStudio()) {
      sdkLocation = IdeSdks.getAndroidSdkPath();
    }
    else {
      AndroidPlatform platform = getConfiguration().getAndroidPlatform();
      if (platform != null) {
        sdkLocation = platform.getSdkData().getLocation();
      }
    }

    if (sdkLocation != null) {
      File emulatorPath = new File(sdkLocation, toolPath(FN_EMULATOR));
      GeneralCommandLine commandLine = new GeneralCommandLine();
      commandLine.setExePath(emulatorPath.getPath());
      if (avdName != null) {
        commandLine.addParameter("-avd");
        commandLine.addParameter(avdName);
      }
      String[] params = ParametersList.parse(commands);
      for (String s : params) {
        if (!s.isEmpty()) {
          commandLine.addParameter(s);
        }
      }
      AvdManager manager = getAvdManagerSilently();
      AvdInfo info = manager == null ? null : manager.getAvd(avdName, true);
      final EmulatorRunner runner = new EmulatorRunner(getModule().getProject(), "AVD: " + avdName, commandLine, info);

      ApplicationManager.getApplication().executeOnPooledThread(new Runnable() {
        @Override
        public void run() {
          try {
            runner.start();
          }
          catch (ExecutionException e) {
            Logger.getInstance(this.getClass()).error("Unexpected error while launching AVD", e);
          }
        }
      });
    }
  }

  public static void createDynamicTemplateMenu() {
    if (ourDynamicTemplateMenuCreated) {
      return;
    }
    ourDynamicTemplateMenuCreated = true;
    DefaultActionGroup newGroup = (DefaultActionGroup)ActionManager.getInstance().getAction("NewGroup");
    newGroup.addSeparator();
    ActionGroup menu = TemplateManager.getInstance().getTemplateCreationMenu(null);

    if (menu != null) {
      newGroup.add(menu, new Constraints(Anchor.AFTER, "NewFromTemplate"));
    }
  }

  @Override
  public void initFacet() {
    StartupManager.getInstance(getModule().getProject()).runWhenProjectIsInitialized(new Runnable() {
        @Override
        public void run() {
          AndroidResourceFilesListener.notifyFacetInitialized(AndroidFacet.this);
          if (ApplicationManager.getApplication().isUnitTestMode()) {
            return;
          }

          addResourceFolderToSdkRootsIfNecessary();

          ApplicationManager.getApplication().executeOnPooledThread(new Runnable() {
            @Override
            public void run() {
              Module module = getModule();
              Project project = module.getProject();
              if (project.isDisposed()) {
                return;
              }

              generate(module, AndroidAutogeneratorMode.AAPT);
              generate(module, AndroidAutogeneratorMode.AIDL);
              generate(module, AndroidAutogeneratorMode.RENDERSCRIPT);
              generate(module, AndroidAutogeneratorMode.BUILDCONFIG);

              activateSourceAutogenerating();
            }
          });
        }
      });

    getModule().getMessageBus().connect(this).subscribe(ProjectTopics.PROJECT_ROOTS, new ModuleRootAdapter() {
      private Sdk myPrevSdk;

      @Override
      public void rootsChanged(ModuleRootEvent event) {
        ApplicationManager.getApplication().invokeLater(new Runnable() {
          @Override
          public void run() {
            if (isDisposed()) {
              return;
            }
            ModuleRootManager rootManager = ModuleRootManager.getInstance(getModule());

            Sdk newSdk = rootManager.getSdk();
            if (newSdk != null && newSdk.getSdkType() instanceof AndroidSdkType && !newSdk.equals(myPrevSdk)) {
              androidPlatformChanged();

              synchronized (myDirtyModes) {
                myDirtyModes.addAll(Arrays.asList(AndroidAutogeneratorMode.values()));
              }
            }
            myPrevSdk = newSdk;
          }
        });
      }
    });
    createDynamicTemplateMenu();
  }

  private void addResourceFolderToSdkRootsIfNecessary() {
    Sdk sdk = ModuleRootManager.getInstance(getModule()).getSdk();
    if (sdk == null || !isAndroidSdk(sdk)) {
      return;
    }

    AndroidPlatform platform = AndroidPlatform.getInstance(sdk);
    if (platform == null) {
      return;
    }

    String resFolderPath = platform.getTarget().getPath(IAndroidTarget.RESOURCES);
    if (resFolderPath == null) {
      return;
    }
    List<VirtualFile> filesToAdd = Lists.newArrayList();

    VirtualFile resFolder = LocalFileSystem.getInstance().findFileByPath(toSystemIndependentName(resFolderPath));
    if (resFolder != null) {
      filesToAdd.add(resFolder);
    }

    if (platform.needToAddAnnotationsJarToClasspath()) {
      String sdkHomePath = toSystemIndependentName(platform.getSdkData().getLocation().getPath());
      VirtualFile annotationsJar = JarFileSystem.getInstance().findFileByPath(sdkHomePath + ANNOTATIONS_JAR_RELATIVE_PATH + JAR_SEPARATOR);
      if (annotationsJar != null) {
        filesToAdd.add(annotationsJar);
      }
    }

    addFilesToSdkIfNecessary(sdk, filesToAdd);
  }

  private static void addFilesToSdkIfNecessary(@NotNull Sdk sdk, @NotNull Collection<VirtualFile> files) {
    List<VirtualFile> newFiles = Lists.newArrayList(files);
    newFiles.removeAll(Arrays.asList(sdk.getRootProvider().getFiles(OrderRootType.CLASSES)));

    if (newFiles.size() > 0) {
      SdkModificator modificator = sdk.getSdkModificator();

      for (VirtualFile file : newFiles) {
        modificator.addRoot(file, OrderRootType.CLASSES);
      }
      modificator.commitChanges();
    }
  }

  @Override
  public void disposeFacet() {
    if (myConfigurationManager != null) {
      Disposer.dispose(myConfigurationManager);
    }
  }

  @Nullable
  public static AndroidFacet getInstance(@NotNull Module module) {
    return FacetManager.getInstance(module).getFacetByType(ID);
  }

  @Nullable
  public static AndroidFacet getInstance(@NotNull ConvertContext context) {
    Module module = context.getModule();
    return module != null ? getInstance(module) : null;
  }

  @Nullable
  public static AndroidFacet getInstance(@NotNull PsiElement element) {
    Module module = getModuleSafely(element);
    return module != null ? getInstance(module) : null;
  }

  @Nullable
  public static AndroidFacet getInstance(@NotNull DomElement element) {
    Module module = element.getModule();
    return module != null ? getInstance(module) : null;
  }

  @Nullable
  public ResourceManager getResourceManager(@Nullable String resourcePackage) {
    return getResourceManager(resourcePackage, null);
  }

  @Nullable
  public ResourceManager getResourceManager(@Nullable String resourcePackage, @Nullable PsiElement contextElement) {
    if (SYSTEM_RESOURCE_PACKAGE.equals(resourcePackage)) {
      return getSystemResourceManager();
    }
    if (contextElement != null && isInAndroidSdk(contextElement)) {
      return getSystemResourceManager();
    }
    return getLocalResourceManager();
  }

  private static boolean isInAndroidSdk(@NonNull PsiElement element) {
    PsiFile file = element.getContainingFile();

    if (file == null) {
      return false;
    }
    VirtualFile virtualFile = file.getVirtualFile();

    if (virtualFile == null) {
      return false;
    }
    ProjectFileIndex projectFileIndex = ProjectRootManager.getInstance(element.getProject()).getFileIndex();
    List<OrderEntry> entries = projectFileIndex.getOrderEntriesForFile(virtualFile);

    for (OrderEntry entry : entries) {
      if (entry instanceof JdkOrderEntry) {
        Sdk sdk = ((JdkOrderEntry)entry).getJdk();

        if (sdk != null && sdk.getSdkType() instanceof AndroidSdkType) {
          return true;
        }
      }
    }
    return false;
  }

  @NotNull
  public LocalResourceManager getLocalResourceManager() {
    if (myLocalResourceManager == null) {
      myLocalResourceManager = new LocalResourceManager(this);
    }
    return myLocalResourceManager;
  }

  @Nullable
  public SystemResourceManager getSystemResourceManager() {
    return getSystemResourceManager(true);
  }

  @Nullable
  public SystemResourceManager getSystemResourceManager(boolean publicOnly) {
    if (publicOnly) {
      if (myPublicSystemResourceManager == null) {
        AndroidPlatform platform = getConfiguration().getAndroidPlatform();
        if (platform != null) {
          myPublicSystemResourceManager = new SystemResourceManager(this.getModule().getProject(), platform, true);
        }
      }
      return myPublicSystemResourceManager;
    }
    if (myFullSystemResourceManager == null) {
      AndroidPlatform platform = getConfiguration().getAndroidPlatform();
      if (platform != null) {
        myFullSystemResourceManager = new SystemResourceManager(this.getModule().getProject(), platform, false);
      }
    }
    return myFullSystemResourceManager;
  }

  @Nullable
  public Manifest getManifest() {
    File manifestIoFile = getMainSourceProvider().getManifestFile();

    VirtualFile manifestFile = LocalFileSystem.getInstance().findFileByIoFile(manifestIoFile);
    return manifestFile != null ? loadDomElement(getModule(), manifestFile, Manifest.class) : null;
  }

  @NotNull
  public static AndroidFacetType getFacetType() {
    return (AndroidFacetType)FacetTypeRegistry.getInstance().findFacetType(ID);
  }

  // TODO: correctly support classes from external non-platform jars
  @NotNull
  public Map<String, PsiClass> getClassMap(@NotNull final String className, @NotNull final ClassMapConstructor constructor) {
    synchronized (myClassMapLock) {
      CachedValue<Map<String, PsiClass>> value = myClassMaps.get(className);

      if (value == null) {
        value = CachedValuesManager.getManager(getModule().getProject()).createCachedValue(
          new CachedValueProvider<Map<String, PsiClass>>() {
          @Nullable
          @Override
          public Result<Map<String, PsiClass>> compute() {
            Map<String, PsiClass> map = computeClassMap(className, constructor);
            return Result.create(map, PsiModificationTracker.JAVA_STRUCTURE_MODIFICATION_COUNT);
          }
        }, false);
        myClassMaps.put(className, value);
      }
      return value.getValue();
    }
  }

  @NotNull
  private Map<String, PsiClass> computeClassMap(@NotNull String className, @NotNull ClassMapConstructor constructor) {
    Map<String, SmartPsiElementPointer<PsiClass>> classMap = getInitialClassMap(className, constructor, false);
    Map<String, PsiClass> result = Maps.newHashMap();
    boolean shouldRebuildInitialMap = false;

    for (String key : classMap.keySet()) {
      SmartPsiElementPointer<PsiClass> pointer = classMap.get(key);

      if (!isUpToDate(pointer, key, constructor)) {
        shouldRebuildInitialMap = true;
        break;
      }
      PsiClass aClass = pointer.getElement();

      if (aClass != null) {
        result.put(key, aClass);
      }
    }

    if (shouldRebuildInitialMap) {
      result.clear();
      classMap = getInitialClassMap(className, constructor, true);

      for (String key : classMap.keySet()) {
        SmartPsiElementPointer<PsiClass> pointer = classMap.get(key);
        PsiClass aClass = pointer.getElement();

        if (aClass != null) {
          result.put(key, aClass);
        }
      }
    }
    Project project = getModule().getProject();
    fillMap(className, constructor, ProjectScope.getProjectScope(project), result, false);
    return result;
  }

  private static boolean isUpToDate(SmartPsiElementPointer<PsiClass> pointer, String tagName, ClassMapConstructor constructor) {
    PsiClass aClass = pointer.getElement();
    if (aClass == null) {
      return false;
    }
    String[] tagNames = constructor.getTagNamesByClass(aClass);
    return find(tagNames, tagName) >= 0;
  }

  @NotNull
  private Map<String, SmartPsiElementPointer<PsiClass>> getInitialClassMap(@NotNull String className,
                                                                           @NotNull ClassMapConstructor constructor,
                                                                           boolean forceRebuild) {
    Map<String, SmartPsiElementPointer<PsiClass>> viewClassMap = myInitialClassMaps.get(className);
    if (viewClassMap != null && !forceRebuild) return viewClassMap;
    Map<String, PsiClass> map = Maps.newHashMap();

    if (fillMap(className, constructor, getModule().getModuleWithDependenciesAndLibrariesScope(true), map, true)) {
      viewClassMap = Maps.newHashMapWithExpectedSize(map.size());
      SmartPointerManager manager = SmartPointerManager.getInstance(getModule().getProject());

      for (Map.Entry<String, PsiClass> entry : map.entrySet()) {
        viewClassMap.put(entry.getKey(), manager.createSmartPsiElementPointer(entry.getValue()));
      }
      myInitialClassMaps.put(className, viewClassMap);
    }
    return viewClassMap != null
           ? viewClassMap
           : Collections.<String, SmartPsiElementPointer<PsiClass>>emptyMap();
  }

  private boolean fillMap(@NotNull final String className,
                          @NotNull final ClassMapConstructor constructor,
                          @NotNull GlobalSearchScope scope,
                          final Map<String, PsiClass> map,
                          final boolean libClassesOnly) {
    final JavaPsiFacade facade = JavaPsiFacade.getInstance(getModule().getProject());
    PsiClass baseClass = ApplicationManager.getApplication().runReadAction(new Computable<PsiClass>() {
      @Override
      @Nullable
      public PsiClass compute() {
        return facade.findClass(className, getModule().getModuleWithDependenciesAndLibrariesScope(true));
      }
    });
    if (baseClass != null) {
      String[] baseClassTagNames = constructor.getTagNamesByClass(baseClass);
      for (String tagName : baseClassTagNames) {
        map.put(tagName, baseClass);
      }
      try {
        ClassInheritorsSearch.search(baseClass, scope, true).forEach(new Processor<PsiClass>() {
          @Override
          public boolean process(PsiClass c) {
            if (libClassesOnly && c.getManager().isInProject(c)) {
              return true;
            }
            String[] tagNames = constructor.getTagNamesByClass(c);
            for (String tagName : tagNames) {
              map.put(tagName, c);
            }
            return true;
          }
        });
      }
      catch (IndexNotReadyException e) {
        LOG.info(e);
        return false;
      }
    }
    return map.size() > 0;
  }


  public void scheduleSourceRegenerating(@NotNull AndroidAutogeneratorMode mode) {
    synchronized (myDirtyModes) {
      myDirtyModes.add(mode);
    }
  }

  public boolean cleanRegeneratingState(@NotNull AndroidAutogeneratorMode mode) {
    synchronized (myDirtyModes) {
      return myDirtyModes.remove(mode);
    }
  }

  @NotNull
  public ConfigurationManager getConfigurationManager() {
    return getConfigurationManager(true);
  }


  @Contract("true -> !null")
  @Nullable
  public ConfigurationManager getConfigurationManager(boolean createIfNecessary) {
    if (myConfigurationManager == null && createIfNecessary) {
      myConfigurationManager = ConfigurationManager.create(getModule());
      Disposer.register(this, myConfigurationManager);
    }

    return myConfigurationManager;
  }

  @Contract("true -> !null")
  @Nullable
  public AppResourceRepository getAppResources(boolean createIfNecessary) {
    synchronized (APP_RESOURCES_LOCK) {
      if (myAppResources == null && createIfNecessary) {
        myAppResources = AppResourceRepository.create(this);
      }
      return myAppResources;
    }
  }

  @Contract("true -> !null")
  @Nullable
  public ProjectResourceRepository getProjectResources(boolean createIfNecessary) {
    synchronized (PROJECT_RESOURCES_LOCK) {
      if (myProjectResources == null && createIfNecessary) {
        myProjectResources = ProjectResourceRepository.create(this);
      }
      return myProjectResources;
    }
  }

  @Contract("true -> !null")
  @Nullable
  public LocalResourceRepository getModuleResources(boolean createIfNecessary) {
    synchronized (MODULE_RESOURCES_LOCK) {
      if (myModuleResources == null && createIfNecessary) {
        myModuleResources = ModuleResourceRepository.create(this);
      }
      return myModuleResources;
    }
  }

  public void refreshResources() {
    myModuleResources = null;
    myProjectResources = null;
    myAppResources = null;
    myConfigurationManager.getResolverCache().reset();
    ResourceFolderRegistry.reset();
    FileResourceRepository.reset();
  }

  @NotNull
  public JpsAndroidModuleProperties getProperties() {
    JpsAndroidModuleProperties state = getConfiguration().getState();
    assert state != null;
    return state;
  }

  /**
   * Associates the given Android-Gradle project to this facet.
   *
   * @param project the new project.
   */
  public void setIdeaAndroidProject(@Nullable IdeaAndroidProject project) {
    myIdeaAndroidProject = project;
    myDataBindingPsiPackages.clear();
    DataBindingUtil.onIdeaProjectSet(this);
  }

  public void addListener(@NotNull GradleSyncListener listener) {
    Module module = getModule();
    MessageBusConnection connection = module.getProject().getMessageBus().connect(module);
    connection.subscribe(GradleSyncState.GRADLE_SYNC_TOPIC, listener);
  }

  /**
   * Returns a {@linkplain PsiPackage} instance for the given package name.
   * <p>
   * If it does not exist in the cache, a new one is created.
   *
   * @param packageName The qualified package name
   * @return A {@linkplain PsiPackage} that represents the given qualified name
   */
  public synchronized PsiPackage getOrCreateDataBindingPsiPackage(String packageName) {
    PsiPackage pkg = myDataBindingPsiPackages.get(packageName);
    if (pkg == null) {
      pkg = new PsiPackageImpl(PsiManager.getInstance(getModule().getProject()), packageName) {
        @Override
        public boolean isValid() {
          return true;
        }
      };
      myDataBindingPsiPackages.put(packageName, pkg);
    }
    return pkg;
  }

  /**
   * @return the Android-Gradle project associated to this facet.
   */
  @Nullable
  public IdeaAndroidProject getIdeaAndroidProject() {
    return myIdeaAndroidProject;
  }

  public void syncSelectedVariantAndTestArtifact() {
    if (myIdeaAndroidProject != null) {
      Variant variant = myIdeaAndroidProject.getSelectedVariant();
      JpsAndroidModuleProperties state = getProperties();
      state.SELECTED_BUILD_VARIANT = variant.getName();
      state.SELECTED_TEST_ARTIFACT = myIdeaAndroidProject.getSelectedTestArtifactName();

      AndroidArtifact mainArtifact = variant.getMainArtifact();
      BaseArtifact testArtifact = myIdeaAndroidProject.findSelectedTestArtifactInSelectedVariant();
      updateGradleTaskNames(state, mainArtifact, testArtifact);
    }
  }

  @VisibleForTesting
  static void updateGradleTaskNames(@NotNull JpsAndroidModuleProperties state,
                                    @NotNull AndroidArtifact mainArtifact,
                                    @Nullable BaseArtifact testArtifact) {
    state.ASSEMBLE_TASK_NAME = mainArtifact.getAssembleTaskName();
    state.COMPILE_JAVA_TASK_NAME = mainArtifact.getCompileTaskName();
    state.AFTER_SYNC_TASK_NAMES = Sets.newHashSet(getIdeSetupTasks(mainArtifact));

    if (testArtifact != null) {
      state.ASSEMBLE_TEST_TASK_NAME = testArtifact.getAssembleTaskName();
      state.COMPILE_JAVA_TEST_TASK_NAME = testArtifact.getCompileTaskName();
      state.AFTER_SYNC_TASK_NAMES.addAll(getIdeSetupTasks(testArtifact));
    }
    else {
      state.ASSEMBLE_TEST_TASK_NAME = "";
      state.COMPILE_JAVA_TEST_TASK_NAME = "";
    }
  }

  private static @NotNull Set<String> getIdeSetupTasks(@NotNull BaseArtifact artifact) {
    try {
      // This method was added in 1.1 - we have to handle the case when it's missing on the Gradle side.
      return artifact.getIdeSetupTaskNames();
    }
    catch (NoSuchMethodError e) {
      if (artifact instanceof AndroidArtifact) {
        return Sets.newHashSet(((AndroidArtifact)artifact).getSourceGenTaskName());
      }
    }
    catch (UnsupportedMethodException e) {
      if (artifact instanceof AndroidArtifact) {
        return Sets.newHashSet(((AndroidArtifact)artifact).getSourceGenTaskName());
      }
    }

    return Collections.emptySet();
  }

  /**
   * @return The generated in-memory R class for this facet, if one exists.
   */
  @Nullable
  public PsiClass getLightRClass() {
    return myLightRClass;
  }

  /**
   * Sets the generated in-memory R class for this facet.
   */
  public void setLightRClass(@NotNull PsiClass rClass) {
    myLightRClass = rClass;
  }

  @NotNull
  public AndroidModuleInfo getAndroidModuleInfo() {
    return myAndroidModuleInfo;
  }

  @NotNull
  public RenderService getRenderService() {
    if (myRenderService == null) {
      myRenderService = new RenderService(this);
    }
    return myRenderService;
  }

  // Compatibility bridge for old (non-Gradle) projects. Also used in Gradle projects before the module has been synced.
  private class LegacySourceProvider implements SourceProvider {
    @NonNull
    @Override
    public String getName() {
      return "main";
    }

    @NonNull
    @Override
    public File getManifestFile() {
      Module module = getModule();
      VirtualFile manifestFile = getFileByRelativeModulePath(module, getProperties().MANIFEST_FILE_RELATIVE_PATH, true);
      if (manifestFile == null) {
        VirtualFile root = !isGradleProject() ? AndroidRootUtil.getMainContentRoot(AndroidFacet.this) : null;
        if (root != null) {
          return new File(virtualToIoFile(root), ANDROID_MANIFEST_XML);
        } else {
          return new File(ANDROID_MANIFEST_XML);
        }
      } else {
        return virtualToIoFile(manifestFile);
      }
    }

    @NonNull
    @Override
    public Set<File> getJavaDirectories() {
      Set<File> dirs = Sets.newHashSet();

      Module module = getModule();
      VirtualFile[] contentRoots = ModuleRootManager.getInstance(module).getContentRoots();
      if (contentRoots.length != 0) {
        for (VirtualFile root : contentRoots) {
          dirs.add(virtualToIoFile(root));
        }
      }
      return dirs;
    }

    @NonNull
    @Override
    public Set<File> getResourcesDirectories() {
      return Collections.emptySet();
    }

    @NonNull
    @Override
    public Set<File> getAidlDirectories() {
      final VirtualFile dir = getAidlGenDir(AndroidFacet.this);
      return dir == null ? Collections.<File>emptySet() : Collections.singleton(virtualToIoFile(dir));
    }

    @NonNull
    @Override
    public Set<File> getRenderscriptDirectories() {
      VirtualFile dir = AndroidRootUtil.getRenderscriptGenDir(AndroidFacet.this);
      return dir == null ? Collections.<File>emptySet() : Collections.singleton(virtualToIoFile(dir));
    }

    @NonNull
    @Override
    public Set<File> getResDirectories() {
      String resRelPath = getProperties().RES_FOLDER_RELATIVE_PATH;
      VirtualFile dir =  getFileByRelativeModulePath(getModule(), resRelPath, true);
      return dir == null ? Collections.<File>emptySet() : Collections.singleton(virtualToIoFile(dir));
    }

    @NonNull
    @Override
    public Set<File> getAssetsDirectories() {
      final VirtualFile dir = getAssetsDir(AndroidFacet.this);
      return dir == null ? Collections.<File>emptySet() : Collections.singleton(virtualToIoFile(dir));
    }

    @NonNull
    @Override
    public Collection<File> getJniLibsDirectories() {
      return Collections.emptyList();
    }

    @NonNull
    @Override
    public Collection<File> getCDirectories() {
      return Collections.emptyList();
    }

    @NonNull
    @Override
    public Collection<File> getCppDirectories() {
      return Collections.emptyList();
    }
  }

}
