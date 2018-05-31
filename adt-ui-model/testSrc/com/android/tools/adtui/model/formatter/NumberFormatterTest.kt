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
package com.android.tools.adtui.model.formatter

import com.google.common.truth.Truth
import org.junit.Test
import java.util.*

class NumberFormatterTest {

  @Test
  fun testFormatInteger() {
    val defaultLocale = Locale.getDefault()
    Locale.setDefault(Locale.US)
    Truth.assertThat(NumberFormatter.formatInteger(1000)).isEqualTo("1,000")
    Locale.setDefault(defaultLocale)
  }
}