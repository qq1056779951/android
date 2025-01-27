/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.tools.idea.projectsystem

import com.android.tools.idea.io.FilePaths
import com.android.utils.SdkUtils.urlToFile
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SourceProviderUtilTest {

  @get:Rule
  val tempDirectory: TemporaryFolder = TemporaryFolder()

  private lateinit var main: NamedIdeaSourceProvider
  private lateinit var debug: NamedIdeaSourceProvider
  private lateinit var release: NamedIdeaSourceProvider
  private lateinit var test: NamedIdeaSourceProvider
  private lateinit var testDebug: NamedIdeaSourceProvider
  private lateinit var testRelease: NamedIdeaSourceProvider
  private lateinit var androidTest: NamedIdeaSourceProvider
  private lateinit var androidTestDebug: NamedIdeaSourceProvider

  @Before
  fun setup() {
    main = createSourceProviderAt("main", ScopeType.MAIN)
    debug = createSourceProviderAt("debug", ScopeType.MAIN)
    release = createSourceProviderAt("release", ScopeType.MAIN)
    test = createSourceProviderAt("test", ScopeType.UNIT_TEST)
    testDebug = createSourceProviderAt("testDebug", ScopeType.UNIT_TEST)
    testRelease = createSourceProviderAt("testRelease", ScopeType.UNIT_TEST)
    androidTest = createSourceProviderAt("androidTest", ScopeType.ANDROID_TEST)
    androidTestDebug = createSourceProviderAt("androidTestDebug", ScopeType.ANDROID_TEST)
  }

  @Test
  fun templates_forMain() {
    val sourceProviders = createSourceProviders()
    val templates = sourceProviders.buildNamedModuleTemplatesFor(moduleRoot, listOf(main))
    assertThat(templates).hasSize(1)
    val mainTemplate = templates[0]
    assertThat(mainTemplate.name).isEqualTo("main")
    assertThat(mainTemplate.paths.getSrcDirectory("com.example"))
      .isEqualTo(main.javaDirectoryUrls.first().let { urlToFile(it) }.resolve("com/example"))
    assertThat(mainTemplate.paths.getUnitTestDirectory("com.example"))
      .isEqualTo(test.javaDirectoryUrls.first().let { urlToFile(it) }.resolve("com/example"))
    assertThat(mainTemplate.paths.getTestDirectory("com.example"))
      .isEqualTo(androidTest.javaDirectoryUrls.first().let { urlToFile(it) }.resolve("com/example"))
    assertThat(mainTemplate.paths.mlModelsDirectories).isEqualTo(main.mlModelsDirectoryUrls.map { urlToFile(it) } )
  }

  @Test
  fun templates_forDebug() {
    val sourceProviders = createSourceProviders()
    val templates = sourceProviders.buildNamedModuleTemplatesFor(moduleRoot, listOf(debug))
    assertThat(templates).hasSize(1)
    val mainTemplate = templates[0]
    assertThat(mainTemplate.name).isEqualTo("debug")
    assertThat(mainTemplate.paths.getSrcDirectory("com.example"))
      .isEqualTo(debug.javaDirectoryUrls.first().let { urlToFile(it) }.resolve("com/example"))
    assertThat(mainTemplate.paths.getUnitTestDirectory("com.example"))
      .isEqualTo(testDebug.javaDirectoryUrls.first().let { urlToFile(it) }.resolve("com/example"))
    assertThat(mainTemplate.paths.getTestDirectory("com.example"))
      .isEqualTo(androidTestDebug.javaDirectoryUrls.first().let { urlToFile(it) }.resolve("com/example"))
  }

  @Test
  fun templates_forRelease() {
    val sourceProviders = createSourceProviders()
    val templates = sourceProviders.buildNamedModuleTemplatesFor(moduleRoot, listOf(release))
    assertThat(templates).hasSize(1)
    val mainTemplate = templates[0]
    assertThat(mainTemplate.name).isEqualTo("release")
    assertThat(mainTemplate.paths.getSrcDirectory("com.example"))
      .isEqualTo(release.javaDirectoryUrls.first().let { urlToFile(it) }.resolve("com/example"))
    // `testRelease` source provider is not available in any collection and therefore test directory location in unknown.
    assertThat(mainTemplate.paths.getUnitTestDirectory("com.example")).isNull()
    // `androidTestRelease` source provider is not available in any collection and therefore test directory location in unknown.
    assertThat(mainTemplate.paths.getTestDirectory("com.example")).isNull()
  }

  @Test
  fun templates_forTest() {
    val sourceProviders = createSourceProviders()
    val templates = sourceProviders.buildNamedModuleTemplatesFor(moduleRoot, listOf(test))
    assertThat(templates).hasSize(1)
    val mainTemplate = templates[0]
    assertThat(mainTemplate.name).isEqualTo("test")
    // No source directory is available for test source providers.
    assertThat(mainTemplate.paths.getSrcDirectory("com.example")).isNull()
    assertThat(mainTemplate.paths.getUnitTestDirectory("com.example"))
      .isEqualTo(test.javaDirectoryUrls.first().let { urlToFile(it) }.resolve("com/example"))
    assertThat(mainTemplate.paths.getTestDirectory("com.example"))
      .isEqualTo(androidTest.javaDirectoryUrls.first().let { urlToFile(it) }.resolve("com/example"))
  }

  private val moduleRoot: File get() = tempDirectory.root

  private fun createSourceProviders(): SourceProviders =
    SourceProvidersImpl(
      mainIdeaSourceProvider = main,
      currentSourceProviders = listOf(main, debug),
      currentUnitTestSourceProviders = listOf(test, testDebug),
      currentAndroidTestSourceProviders = listOf(androidTest, androidTestDebug),
      currentAndSomeFrequentlyUsedInactiveSourceProviders = listOf(main, debug, release),
      mainAndFlavorSourceProviders = listOf(main)
    )

  private fun createSourceProviderAt(
    name: String,
    scopeType: ScopeType,
    root: File = moduleRoot.resolve(name),
    manifestFile: File = File("AndroidManifest.xml"),
    javaDirectories: List<File> = listOf(File("java")),
    resourcesDirectories: List<File> = listOf(File("resources")),
    aidlDirectories: List<File> = listOf(File("aidl")),
    renderScriptDirectories: List<File> = listOf(File("rs")),
    jniDirectories: List<File> = listOf(File("jni")),
    resDirectories: List<File> = listOf(File("res")),
    assetsDirectories: List<File> = listOf(File("assets")),
    shadersDirectories: List<File> = listOf(File("shaders")),
    mlModelsDirectories: List<File> = listOf(File("ml"))
  ) =
    NamedIdeaSourceProviderImpl(
      name,
      scopeType,
      manifestFileUrl = root.resolve(manifestFile).toIdeaUrl(),
      javaDirectoryUrls = javaDirectories.map { root.resolve(it).toIdeaUrl() },
      resourcesDirectoryUrls = resourcesDirectories.map { root.resolve(it).toIdeaUrl() },
      aidlDirectoryUrls = aidlDirectories.map { root.resolve(it).toIdeaUrl() },
      renderscriptDirectoryUrls = renderScriptDirectories.map { root.resolve(it).toIdeaUrl() },
      jniDirectoryUrls = jniDirectories.map { root.resolve(it).toIdeaUrl() },
      resDirectoryUrls = resDirectories.map { root.resolve(it).toIdeaUrl() },
      assetsDirectoryUrls = assetsDirectories.map { root.resolve(it).toIdeaUrl() },
      shadersDirectoryUrls = shadersDirectories.map { root.resolve(it).toIdeaUrl() },
      mlModelsDirectoryUrls = mlModelsDirectories.map { root.resolve(it).toIdeaUrl() }
    )
}

private fun File.toIdeaUrl(): String = FilePaths.pathToIdeaUrl(this)