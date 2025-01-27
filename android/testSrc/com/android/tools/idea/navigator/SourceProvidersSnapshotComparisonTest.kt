/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tools.idea.navigator

import com.android.builder.model.SourceProvider
import com.android.tools.idea.model.AndroidModel
import com.android.tools.idea.testing.*
import com.android.tools.idea.util.toIoFile
import com.android.testutils.TestUtils
import com.android.tools.idea.gradle.project.model.AndroidModuleModel
import com.android.tools.idea.projectsystem.IdeaSourceProvider
import com.android.tools.idea.projectsystem.NamedIdeaSourceProvider
import com.android.tools.idea.testing.AndroidGradleTestCase
import com.android.tools.idea.testing.AndroidGradleTests
import com.android.tools.idea.testing.SnapshotComparisonTest
import com.android.tools.idea.testing.TestProjectToSnapshotPaths
import com.android.tools.idea.testing.assertIsEqualToSnapshot
import com.android.utils.FileUtils
import com.intellij.ide.impl.ProjectUtil
import com.intellij.idea.Bombed
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil.toSystemDependentName
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import org.jetbrains.android.facet.AndroidFacet
import org.jetbrains.android.facet.SourceProviderManager
import org.jetbrains.android.facet.getManifestFiles
import org.jetbrains.annotations.SystemIndependent
import java.io.File

/**
 * Snapshot tests for 'Source Providers'.
 *
 * The pre-recorded sync results can be found in testData/sourceProvidersSnapshots/ *.txt files.
 *
 * NOTE: It you made changes to sync or the test projects which make these tests fail in an expected way, you can re-run the tests
 *       from IDE with -DUPDATE_TEST_SNAPSHOTS to update the files.
 *
 *       Or with bazel:
bazel test //tools/adt/idea/android:intellij.android.core.tests_tests  --test_sharding_strategy=disabled  \
--test_filter="SourceProvidersSnapshotComparisonTest" --nocache_test_results --strategy=TestRunner=standalone \
--jvmopt='-DUPDATE_TEST_SNAPSHOTS' --test_output=streamed
 */
class SourceProvidersSnapshotComparisonTest : AndroidGradleTestCase(), SnapshotComparisonTest {
  override val snapshotDirectoryAdtIdeaRelativePath: String = "android/testData/snapshots/sourceProviders"
  override fun getTestDataDirectoryAdtIdeaRelativePath(): @SystemIndependent String = "android/testData/snapshots"

  fun testSimpleApplication() {
    val text = importSyncAndDumpProject(TestProjectToSnapshotPaths.SIMPLE_APPLICATION)
    assertIsEqualToSnapshot(text)
  }

  fun testWithMlModels() {
    val text = importSyncAndDumpProject(TestProjectToSnapshotPaths.APP_WITH_ML_MODELS)
    assertIsEqualToSnapshot(text)
  }

  fun testMultiFlavor() {
    val text = importSyncAndDumpProject(TestProjectToSnapshotPaths.MULTI_FLAVOR)
    assertIsEqualToSnapshot(text)
  }

  fun testNestedProjects() {
    val text = importSyncAndDumpProject(TestProjectToSnapshotPaths.PSD_SAMPLE_GROOVY)
    assertIsEqualToSnapshot(text)
  }

  fun testCompositeBuild() {
    val text = importSyncAndDumpProject(TestProjectToSnapshotPaths.COMPOSITE_BUILD)
    assertIsEqualToSnapshot(text)
  }

  @Bombed(year = 2021, month = 4, day = 6, user = "andrei.kuznetsov", description = "Bomb slow muted tests in IDEA to speed up")
  fun testWithBuildSrc() {
    val text = importSyncAndDumpProject(TestProjectToSnapshotPaths.APP_WITH_BUILDSRC)
    assertIsEqualToSnapshot(text)
  }

  fun testDependentNativeModules() {
    val text = importSyncAndDumpProject(TestProjectToSnapshotPaths.DEPENDENT_NATIVE_MODULES)
    assertIsEqualToSnapshot(text)
  }

  fun testJpsWithQualifiedNames() {
    val srcPath = File(myFixture.testDataPath, toSystemDependentName(TestProjectToSnapshotPaths.JPS_WITH_QUALIFIED_NAMES))
    // Prepare project in a different directory (_jps) to avoid closing the currently opened project.
    val projectPath = File(toSystemDependentName(project.basePath + "_jps"))

    AndroidGradleTests.prepareProjectForImportCore(srcPath, projectPath) { projectRoot ->
      // Override settings just for tests (e.g. sdk.dir)
      AndroidGradleTests.updateLocalProperties(projectRoot, TestUtils.getSdk())
    }

    val project = PlatformTestUtil.loadAndOpenProject(projectPath.toPath(), testRootDisposable)
    val text = project.dumpSourceProviders()
    PlatformTestUtil.forceCloseProjectWithoutSaving(project)

    assertIsEqualToSnapshot(text)
  }

  fun testCompatibilityWithAndroidStudio36Project() {
    val text = importSyncAndDumpProject(TestProjectToSnapshotPaths.COMPATIBILITY_TESTS_AS_36)
    assertIsEqualToSnapshot(text)
  }

  fun testCompatibilityWithAndroidStudio36NoImlProject() {
    val text = importSyncAndDumpProject(TestProjectToSnapshotPaths.COMPATIBILITY_TESTS_AS_36_NO_IML)
    assertIsEqualToSnapshot(text)
  }

  private fun importSyncAndDumpProject(
    projectDir: String,
    patch: ((projectRootPath: File) -> Unit)? = null
  ): String {
    val projectRootPath = prepareProjectForImport(projectDir)
    patch?.invoke(projectRootPath)
    importProject()

    return project.dumpSourceProviders()
  }

  @Suppress("DEPRECATION")
  private fun Project.dumpSourceProviders(): String {
    val projectRootPath = File(basePath)
    return buildString {
      var prefix = ""

      fun out(s: String) = appendln("$prefix$s")

      fun <T> nest(title: String? = null, code: () -> T): T {
        if (title != null) {
          out(title)
        }
        prefix = "    $prefix"
        val result = code()
        prefix = prefix.substring(4)
        return result
      }

      fun String.toPrintablePath(): String = this.replace(projectRootPath.absolutePath.toSystemIndependent(), ".", false)

      fun <T, F> T.dumpPathsCore(name: String, getter: (T) -> Collection<F>, mapper: (F) -> String?) {
        val entries = getter(this)
        if (entries.isEmpty()) return
        out("$name:")
        nest {
          entries
            .mapNotNull(mapper)
            .forEach {
              out(it.toPrintablePath())
            }
        }
      }

      fun SourceProvider.dumpPaths(name: String, getter: (SourceProvider) -> Collection<File>) =
        dumpPathsCore(name, getter) { it.path.toSystemIndependent() }

      fun IdeaSourceProvider.dumpUrls(name: String, getter: (IdeaSourceProvider) -> Collection<String>) =
        dumpPathsCore(name, getter) { it }

      fun IdeaSourceProvider.dumpPaths(name: String, getter: (IdeaSourceProvider) -> Collection<VirtualFile?>) =
        dumpPathsCore(name, getter) { it?.url }

      fun SourceProvider.dump() {
        out(name)
        nest {
          dumpPaths("Manifest") { listOf(manifestFile) }
          dumpPaths("AidlDirectories") { it.aidlDirectories }
          dumpPaths("AssetsDirectories") { it.assetsDirectories }
          dumpPaths("CDirectories") { it.cDirectories }
          dumpPaths("CppDirectories") { it.cppDirectories }
          dumpPaths("JavaDirectories") { it.javaDirectories }
          dumpPaths("JniLibsDirectories") { it.jniLibsDirectories }
          dumpPaths("RenderscriptDirectories") { it.renderscriptDirectories }
          dumpPaths("ResDirectories") { it.resDirectories }
          dumpPaths("ResourcesDirectories") { it.resourcesDirectories }
          dumpPaths("ShadersDirectories") { it.shadersDirectories }
          dumpPaths("MlModelsDirectories") { it.mlModelsDirectories }
        }
      }

      fun IdeaSourceProvider.dump(name: String) {
        out("${name} (IDEA)")
        nest {
          out("ScopeType: $scopeType")
          dumpUrls("ManifestFileUrls") { it.manifestFileUrls }
          dumpPaths("ManifestFiles") { it.manifestFiles }
          dumpUrls("ManifestDirectoryUrls") { it.manifestDirectoryUrls }
          dumpPaths("ManifestDirectories") { it.manifestDirectories }
          dumpUrls("AidlDirectoryUrls") { it.aidlDirectoryUrls }
          dumpPaths("AidlDirectories") { it.aidlDirectories }
          dumpUrls("AssetsDirectoryUrls") { it.assetsDirectoryUrls }
          dumpPaths("AssetsDirectories") { it.assetsDirectories }
          dumpUrls("JavaDirectoryUrls") { it.javaDirectoryUrls }
          dumpPaths("JavaDirectories") { it.javaDirectories }
          dumpUrls("JniLibsDirectoryUrls") { it.jniLibsDirectoryUrls }
          dumpPaths("JniLibsDirectories") { it.jniLibsDirectories }
          dumpUrls("RenderscriptDirectoryUrls") { it.renderscriptDirectoryUrls }
          dumpPaths("RenderscriptDirectories") { it.renderscriptDirectories }
          dumpUrls("ResDirectoryUrls") { it.resDirectoryUrls }
          dumpPaths("ResDirectories") { it.resDirectories }
          dumpUrls("ResourcesDirectoryUrls") { it.resourcesDirectoryUrls }
          dumpPaths("ResourcesDirectories") { it.resourcesDirectories }
          dumpUrls("ShadersDirectoryUrls") { it.shadersDirectoryUrls }
          dumpPaths("ShadersDirectories") { it.shadersDirectories }
          dumpUrls("MlModelsDirectoryUrls") { it.mlModelsDirectoryUrls }
          dumpPaths("MlModelsDirectories") { it.mlModelsDirectories }
        }
      }

      fun NamedIdeaSourceProvider.dump() {
        dump(name)
      }

      ModuleManager
        .getInstance(this@dumpSourceProviders)
        .modules
        .sortedBy { it.name }
        .forEach { module ->
          out("MODULE: ${module.name}")
          val androidFacet = AndroidFacet.getInstance(module)
          if (androidFacet != null) {
            nest {
              nest("by Facet:") {
                val sourceProviderManager = SourceProviderManager.getInstance(androidFacet)
                sourceProviderManager.mainIdeaSourceProvider.dump()
              }
              val model = AndroidModuleModel.get(module)

              fun SourceProvider.adjustedName() =
                if (name == "main") "_" else name
              fun NamedIdeaSourceProvider.adjustedName() =
                if (name == "main") "_" else name

              if (model != null) {
                nest("by AndroidModel:") {
                  model.defaultSourceProvider.dump()
                  nest("Active:") { model.activeSourceProviders.forEach { it.dump() } }
                  nest("All:") { model.allSourceProviders.sortedBy { it.adjustedName() }.forEach { it.dump() } }
                  nest("UnitTest:") { model.unitTestSourceProviders.forEach { it.dump() } }
                  nest("AndroidTest:") { model.androidTestSourceProviders.forEach { it.dump() } }
                }
              }
              nest("by IdeaSourceProviders:") {
                val sourceProviderManager = SourceProviderManager.getInstance(androidFacet)
                dumpPathsCore("Manifests", { getManifestFiles(androidFacet) }, { it.url })
                nest("Sources:") { sourceProviderManager.sources.dump("Sources") }
                nest("UnitTestSources:") { sourceProviderManager.unitTestSources.dump("UnitTestSources") }
                nest("AndroidTestSources:") { sourceProviderManager.androidTestSources.dump("AndroidTestSources") }
                nest("CurrentAndSomeFrequentlyUsedInactiveSourceProviders:") { sourceProviderManager.currentAndSomeFrequentlyUsedInactiveSourceProviders.sortedBy { it.adjustedName() }.forEach { it.dump() } }
                nest("CurrentSourceProviders:") { sourceProviderManager.currentSourceProviders.forEach { it.dump() } }
                nest("CurrentUnitTestSourceProviders:") { sourceProviderManager.currentUnitTestSourceProviders.forEach { it.dump() } }
                nest("CurrentAndroidTestSourceProviders:") { sourceProviderManager.currentAndroidTestSourceProviders.forEach { it.dump() } }
              }
            }
          }
        }
    }
      .trimIndent()
  }
}

private fun String.toSystemIndependent() = FileUtils.toSystemIndependentPath(this)
