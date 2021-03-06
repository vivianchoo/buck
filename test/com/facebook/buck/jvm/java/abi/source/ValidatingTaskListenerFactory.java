/*
 * Copyright 2017-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.jvm.java.abi.source;

import com.facebook.buck.jvm.java.abi.source.api.BootClasspathOracle;
import com.facebook.buck.jvm.java.plugin.adapter.BuckJavacTask;
import com.facebook.buck.jvm.java.plugin.adapter.BuckJavacTaskProxyImpl;
import com.facebook.buck.jvm.java.testutil.compiler.CompilerTreeApiTest;
import com.sun.source.util.TaskListener;

import javax.tools.Diagnostic;

class ValidatingTaskListenerFactory implements CompilerTreeApiTest.TaskListenerFactory {
  @Override
  public TaskListener newTaskListener(BuckJavacTask task) {
    return new ValidatingTaskListener(new BuckJavacTaskProxyImpl(task), new BootClasspathOracle() {
      @Override
      public boolean isOnBootClasspath(String binaryName) {
        return binaryName.startsWith("java.");
      }
    }, Diagnostic.Kind.ERROR);
  }
}
