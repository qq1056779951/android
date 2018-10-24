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
package com.android.tools.idea.run;

import com.android.tools.deploy.swapper.ApkFileDatabase;
import com.android.tools.deploy.swapper.SqlApkFileDatabase;
import com.android.tools.deployer.tasks.TaskRunner;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.project.Project;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jetbrains.annotations.NotNull;

public class DeploymentService {

  private final Project project;

  private final ExecutorService service;

  private final TaskRunner runner;

  private final ApkFileDatabase dexDatabase;

  private DeploymentService(@NotNull Project project) {
    this.project = project;
    service = Executors.newFixedThreadPool(5);
    runner = new TaskRunner(service);
    Path path = Paths.get(PathManager.getSystemPath(), ".deploy.db");
    dexDatabase  = new SqlApkFileDatabase(new File(path.toString()));
  }

  public TaskRunner getTaskRunner() {
    return runner;
  }

  public ApkFileDatabase getDexDatabase() {
    return dexDatabase;
  }
}
