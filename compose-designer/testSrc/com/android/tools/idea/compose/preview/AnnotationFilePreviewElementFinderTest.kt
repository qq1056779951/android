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
package com.android.tools.idea.compose.preview

import com.android.tools.idea.compose.ComposeProjectRule
import com.android.tools.idea.compose.preview.util.ParametrizedPreviewElementTemplate
import com.android.tools.idea.compose.preview.util.UNDEFINED_API_LEVEL
import com.android.tools.idea.compose.preview.util.UNDEFINED_DIMENSION
import com.android.tools.idea.compose.preview.util.previewElementComparatorByDisplayName
import com.android.tools.idea.compose.preview.util.previewElementComparatorBySourcePosition
import com.android.tools.idea.flags.StudioFlags
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbServiceImpl
import com.intellij.openapi.util.TextRange
import com.intellij.psi.impl.source.tree.injected.changesHandler.range
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.uast.UFile
import org.jetbrains.uast.toUElement
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Asserts that the given [methodName] body has the actual given [actualBodyRange]
 */
private fun assertMethodTextRange(file: UFile, methodName: String, actualBodyRange: TextRange) {
  val range = ReadAction.compute<TextRange, Throwable> {
    file.method(methodName)
      ?.uastBody
      ?.sourcePsi
      ?.textRange!!
  }
  assertNotEquals(range, TextRange.EMPTY_RANGE)
  assertEquals(range, actualBodyRange)
}

private fun <T> computeOnBackground(computable: () -> T): T =
  AppExecutorUtil.getAppExecutorService().submit(computable).get()

class AnnotationFilePreviewElementFinderTest {
  @get:Rule
  val projectRule = ComposeProjectRule()
  private val project get() = projectRule.project
  private val fixture get() = projectRule.fixture

  @Before
  fun setUp() {
    StudioFlags.COMPOSE_PREVIEW_DATA_SOURCES.override(true)
  }

  @After
  fun tearDown() {
    StudioFlags.COMPOSE_PREVIEW_DATA_SOURCES.clearOverride()
  }

  @Test
  fun testFindPreviewAnnotations() {
    val composeTest = fixture.addFileToProject(
      "src/Test.kt",
      // language=kotlin
      """
        import androidx.ui.tooling.preview.Preview
        import androidx.compose.Composable

        @Composable
        @Preview
        fun Preview1() {
        }

        @Composable
        @Preview(name = "preview2", apiLevel = 12, group = "groupA", showBackground = true)
        fun Preview2() {
        }

        @Composable
        @Preview(name = "preview3", widthDp = 1, heightDp = 2, fontScale = 0.2f, showDecoration = true)
        fun Preview3() {
        }

        // This preview element will be found but the ComposeViewAdapter won't be able to render it
        @Composable
        @Preview(name = "Preview with parameters")
        fun PreviewWithParametrs(i: Int) {
        }

        @Composable
        fun NoPreviewComposable() {

        }

        @Preview
        fun NoComposablePreview() {

        }

        @Composable
        @androidx.ui.tooling.preview.Preview(name = "FQN")
        fun FullyQualifiedAnnotationPreview() {

        }
      """.trimIndent()).toUElement() as UFile

    assertTrue(AnnotationFilePreviewElementFinder.hasPreviewMethods(project, composeTest.sourcePsi.virtualFile))
    assertTrue(computeOnBackground { AnnotationFilePreviewElementFinder.hasPreviewMethods(project, composeTest.sourcePsi.virtualFile) })

    val elements = computeOnBackground { AnnotationFilePreviewElementFinder.findPreviewMethods(composeTest).toList() }
    assertEquals(5, elements.size)
    elements[1].let {
      assertEquals("preview2", it.displaySettings.name)
      assertEquals("groupA", it.displaySettings.group)
      assertEquals(12, it.configuration.apiLevel)
      assertNull(it.configuration.theme)
      assertEquals(UNDEFINED_DIMENSION, it.configuration.width)
      assertEquals(UNDEFINED_DIMENSION, it.configuration.height)
      assertEquals(1f, it.configuration.fontScale)
      assertTrue(it.displaySettings.showBackground)
      assertFalse(it.displaySettings.showDecoration)

      ReadAction.run<Throwable> {
        assertMethodTextRange(composeTest, "Preview2", it.previewBodyPsi?.psiRange?.range!!)
        assertEquals("@Preview(name = \"preview2\", apiLevel = 12, group = \"groupA\", showBackground = true)",
                     it.previewElementDefinitionPsi?.element?.text)
      }
    }

    elements[2].let {
      assertEquals("preview3", it.displaySettings.name)
      assertNull(it.displaySettings.group)
      assertEquals(1, it.configuration.width)
      assertEquals(2, it.configuration.height)
      assertEquals(0.2f, it.configuration.fontScale)
      assertFalse(it.displaySettings.showBackground)
      assertTrue(it.displaySettings.showDecoration)

      ReadAction.run<Throwable> {
        assertMethodTextRange(composeTest, "Preview3", it.previewBodyPsi?.psiRange?.range!!)
        assertEquals("@Preview(name = \"preview3\", widthDp = 1, heightDp = 2, fontScale = 0.2f, showDecoration = true)",
                     it.previewElementDefinitionPsi?.element?.text)
      }
    }

    elements[0].let {
      assertEquals("Preview1", it.displaySettings.name)
      assertEquals(UNDEFINED_API_LEVEL, it.configuration.apiLevel)
      assertEquals(UNDEFINED_DIMENSION, it.configuration.width)
      assertEquals(UNDEFINED_DIMENSION, it.configuration.height)
      assertFalse(it.displaySettings.showBackground)
      assertFalse(it.displaySettings.showDecoration)

      ReadAction.run<Throwable> {
        assertMethodTextRange(composeTest, "Preview1", it.previewBodyPsi?.psiRange?.range!!)
        assertEquals("@Preview", it.previewElementDefinitionPsi?.element?.text)
      }
    }

    elements[3].let {
      assertEquals("Preview with parameters", it.displaySettings.name)
    }

    elements[4].let {
      assertEquals("FQN", it.displaySettings.name)
    }
  }

  @Test
  fun testFindPreviewAnnotationsWithoutImport() {
    val composeTest = fixture.addFileToProject(
      "src/Test.kt",
      // language=kotlin
      """
        import androidx.compose.Composable

        @Composable
        @androidx.ui.tooling.preview.Preview
        fun Preview1() {
        }
      """.trimIndent()).toUElement() as UFile

    assertTrue(AnnotationFilePreviewElementFinder.hasPreviewMethods(project, composeTest.sourcePsi.virtualFile))

    val elements = AnnotationFilePreviewElementFinder.findPreviewMethods(composeTest)
    elements.single().run {
      assertEquals("TestKt.Preview1", composableMethodFqn)
    }
  }

  @Test
  fun testNoDuplicatePreviewElements() {
    val composeTest = fixture.addFileToProject(
      "src/Test.kt",
      // language=kotlin
      """
        import androidx.ui.tooling.preview.Preview
        import androidx.compose.Composable

        @Composable
        @Preview
        fun Preview1() {
        }

        @Composable
        @Preview(name = "preview2", apiLevel = 12)
        fun Preview1() {
        }
      """.trimIndent()).toUElement() as UFile

    val element = AnnotationFilePreviewElementFinder.findPreviewMethods(composeTest).single()
    // Check that we keep the first element
    assertEquals("Preview1", element.displaySettings.name)
  }

  @Test
  fun testFindPreviewPackage() {
    fixture.addFileToProject(
      "src/com/android/notpreview/Preview.kt",
      // language=kotlin
      """
        package com.android.notpreview

        annotation class Preview(val name: String = "",
                                 val apiLevel: Int = -1,
                                 val theme: String = "",
                                 val widthDp: Int = -1,
                                 val heightDp: Int = -1)
       """.trimIndent())

    val composeTest = fixture.addFileToProject(
      "src/Test.kt",
      // language=kotlin
      """
        import com.android.notpreview.Preview
        import androidx.compose.Composable

        @Composable
        @Preview
        fun Preview1() {
        }

        @Composable
        @Preview(name = "preview2", apiLevel = 12)
        fun Preview2() {
        }

        @Composable
        @Preview(name = "preview3", width = 1, height = 2)
        fun Preview3() {
        }
      """.trimIndent())

    assertEquals(0, AnnotationFilePreviewElementFinder.findPreviewMethods(composeTest.toUElement() as UFile).count())
  }

  /**
   * Ensures that calling findPreviewMethods returns an empty. Although the method is guaranteed to be called under smart mode,
   *
   */
  @Test
  fun testDumbMode() {
    val composeTest = fixture.addFileToProject(
      "src/Test.kt",
      // language=kotlin
      """
        import androidx.compose.Composableimport androidx.ui.tooling.preview.Preview

        @Composable
        @Preview
        fun Preview1() {
        }

        @Composable
        @Preview(name = "preview2", apiLevel = 12)
        fun Preview1() {
        }
      """.trimIndent()).toUElement() as UFile

    runInEdtAndWait {
      DumbServiceImpl.getInstance(project).isDumb = true
      try {
        val elements = AnnotationFilePreviewElementFinder.findPreviewMethods(composeTest)
        assertEquals(0, elements.count())
      }
      finally {
        DumbServiceImpl.getInstance(project).isDumb = false
      }
    }
  }

  @Test
  fun testPreviewParameters() {
    val composeTest = fixture.addFileToProject(
      "src/Test.kt",
      // language=kotlin
      """
        package test

        import androidx.ui.tooling.preview.Preview
        import androidx.ui.tooling.preview.PreviewParameter
        import androidx.ui.tooling.preview.PreviewParameterProvider
        import androidx.compose.Composable

        @Composable
        @Preview
        fun NoParameter() {
        }

        class TestStringProvider: PreviewParameterProvider<String> {
            override val values: Sequence<String> = sequenceOf("A", "B", "C")
        }

        class TestIntProvider: PreviewParameterProvider<Int> {
            override val values: Sequence<String> = sequenceOf(1, 2)
        }

        @Composable
        @Preview
        fun SingleParameter(@PreviewParameter(provider = TestStringProvider::class) aString: String) {
        }

        @Composable
        @Preview
        // Same as SingleParameter but without using "provider" in the annotation
        fun SingleParameterNoName(@PreviewParameter(TestStringProvider::class) aString: String) {
        }

        @Composable
        @Preview
        fun MultiParameter(@PreviewParameter(provider = TestStringProvider::class) aString: String,
                           @PreviewParameter(provider = TestIntProvider::class, limit = 2) aInt: Int) {
        }
      """.trimIndent()).toUElement() as UFile

    val elements = AnnotationFilePreviewElementFinder.findPreviewMethods(composeTest).toList()
    elements[0].let {
      assertFalse(it is ParametrizedPreviewElementTemplate)
      assertEquals("NoParameter", it.displaySettings.name)
    }
    // The next two are the same just using the annotation parameter explicitly in one of them.
    // The resulting PreviewElement should be the same with different name.
    listOf("SingleParameter" to elements[1], "SingleParameterNoName" to elements[2])
      .map { (name, previewElement) -> name to previewElement as ParametrizedPreviewElementTemplate }
      .forEach { (name, previewElement) ->
        assertEquals(name, previewElement.displaySettings.name)
        assertEquals(1, previewElement.parameterProviders.size)
        previewElement.parameterProviders.single { param -> "aString" == param.name }.let { parameter ->
          assertEquals("test.TestStringProvider", parameter.providerClassFqn)
          assertEquals(0, parameter.index)
          assertEquals(Int.MAX_VALUE, parameter.limit)
        }
      }
    (elements[3] as ParametrizedPreviewElementTemplate).let {
      assertEquals("MultiParameter", it.displaySettings.name)
      assertEquals(2, it.parameterProviders.size)
      it.parameterProviders.single { param -> "aInt" == param.name }.let { parameter ->
        assertEquals("test.TestIntProvider", parameter.providerClassFqn)
        assertEquals(1, parameter.index)
        assertEquals(2, parameter.limit)
      }
    }
  }

  @Test
  fun testOrdering() {
    val composeTest = fixture.addFileToProject(
      "src/Test.kt",
      // language=kotlin
      """
        import androidx.ui.tooling.preview.Preview
        import androidx.compose.Composable

        @Composable
        @Preview
        fun C() {
        }

        @Composable
        @Preview
        fun A() {
        }

        @Composable
        @Preview
        fun B() {
        }
      """.trimIndent()).toUElement() as UFile

    ReadAction.run<Throwable> {
      AnnotationFilePreviewElementFinder.findPreviewMethods(composeTest)
        .toMutableList().apply {
          // Randomize to make sure the ordering works
          shuffle()
        }
        .sortedWith(previewElementComparatorBySourcePosition)
        .map { it.composableMethodFqn }
        .toTypedArray()
        .let {
          assertArrayEquals(arrayOf("TestKt.C", "TestKt.A", "TestKt.B"), it)
        }
    }

    AnnotationFilePreviewElementFinder.findPreviewMethods(composeTest)
      .toMutableList().apply {
        // Randomize to make sure the ordering works
        shuffle()
      }
      .sortedWith(previewElementComparatorByDisplayName)
      .map { it.composableMethodFqn }
      .toTypedArray()
      .let {
        assertArrayEquals(arrayOf("TestKt.A", "TestKt.B", "TestKt.C"), it)
      }
  }
}