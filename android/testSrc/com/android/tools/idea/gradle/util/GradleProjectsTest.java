/*
 * Copyright (C) 2013 The Android Open Source Project
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
package com.android.tools.idea.gradle.util;

import static com.android.tools.idea.testing.Facets.createAndAddAndroidFacet;

import com.android.tools.idea.project.AndroidProjectInfo;
import com.intellij.testFramework.PlatformTestCase;
import org.jetbrains.android.facet.AndroidFacet;

/**
 * Tests for {@link GradleProjects}.
 */
public class GradleProjectsTest extends PlatformTestCase {
  public void testIsGradleProjectWithRegularProject() {
    assertFalse(AndroidProjectInfo.getInstance(myProject).requiresAndroidModel());
  }

  public void testIsGradleProject() {
    AndroidFacet facet = createAndAddAndroidFacet(myModule);
    //noinspection deprecation
    facet.getProperties().ALLOW_USER_CONFIGURATION = false;

    assertTrue(AndroidProjectInfo.getInstance(myProject).requiresAndroidModel());
  }

}
