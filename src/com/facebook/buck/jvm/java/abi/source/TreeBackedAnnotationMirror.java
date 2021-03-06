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

import com.facebook.buck.util.liteinfersupport.Nullable;
import com.facebook.buck.util.liteinfersupport.Preconditions;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.DeclaredType;

class TreeBackedAnnotationMirror implements AnnotationMirror {
  private final AnnotationMirror underlyingAnnotationMirror;
  private final TreePath path;
  private final AnnotationTree tree;
  private final TreeBackedElementResolver resolver;

  @Nullable
  private DeclaredType type;
  @Nullable
  private Map<ExecutableElement, TreeBackedAnnotationValue> elementValues;

  TreeBackedAnnotationMirror(
      AnnotationMirror underlyingAnnotationMirror,
      TreePath path,
      TreeBackedElementResolver resolver) {
    this.underlyingAnnotationMirror = underlyingAnnotationMirror;
    this.path = path;
    this.resolver = resolver;

    tree = (AnnotationTree) path.getLeaf();
  }

  @Override
  public DeclaredType getAnnotationType() {
    if (type == null) {
      type =
          (DeclaredType) resolver.getCanonicalType(underlyingAnnotationMirror.getAnnotationType());
    }
    return type;
  }

  @Override
  public Map<ExecutableElement, TreeBackedAnnotationValue> getElementValues() {
    if (elementValues == null) {
      Map<ExecutableElement, TreeBackedAnnotationValue> result = new LinkedHashMap<>();
      Map<String, TreePath> treePaths = new HashMap<>();

      List<? extends ExpressionTree> arguments = tree.getArguments();
      for (ExpressionTree argument : arguments) {
        TreePath valuePath = new TreePath(path, argument);
        if (argument.getKind() != Tree.Kind.ASSIGNMENT) {
          treePaths.put("value", valuePath);
        } else {
          AssignmentTree assignment = (AssignmentTree) argument;
          IdentifierTree nameTree = (IdentifierTree) assignment.getVariable();
          treePaths.put(nameTree.getName().toString(), valuePath);
        }
      }

      for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry
          : underlyingAnnotationMirror.getElementValues().entrySet()) {
        ExecutableElement underlyingKeyElement = entry.getKey();

        TreePath valuePath =
            Preconditions.checkNotNull(treePaths.get(entry.getKey().getSimpleName().toString()));

        result.put(
            resolver.getCanonicalElement(underlyingKeyElement),
            new TreeBackedAnnotationValue(entry.getValue(), valuePath, resolver));
      }

      elementValues = Collections.unmodifiableMap(result);
    }
    return elementValues;
  }
}
