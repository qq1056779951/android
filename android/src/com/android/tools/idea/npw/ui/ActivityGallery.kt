/*
 * Copyright (C) 2018 The Android Open Source Project
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

/**
 * Utility methods to load Template Images and find labels
 */
@file:JvmName("ActivityGallery")
package com.android.tools.idea.npw.ui

import com.android.tools.idea.npw.toWizardFormFactor
import com.android.tools.idea.wizard.template.Template
import com.intellij.openapi.util.IconLoader
import icons.AndroidIcons

fun getTemplateIcon(template: Template): TemplateIcon? {
  if (template == Template.NoActivity) {
    return TemplateIcon(AndroidIcons.Wizards.NoActivity)
  }

  val icon = IconLoader.findIcon(template.thumb().path()) ?: return null
  return TemplateIcon(icon)
}

fun getTemplateTitle(template: Template): String =
  template.name.replace("${template.formFactor.toWizardFormFactor().displayName} ", "")
