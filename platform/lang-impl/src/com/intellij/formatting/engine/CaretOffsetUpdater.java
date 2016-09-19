/*
 * Copyright 2000-2015 JetBrains s.r.o.
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
package com.intellij.formatting.engine;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.TextChange;
import com.intellij.openapi.editor.impl.BulkChangesMerger;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CaretOffsetUpdater {
    private final Map<Editor, Integer> myCaretOffsets = new HashMap<>();

    public CaretOffsetUpdater(@NotNull Document document) {
      Editor[] editors = EditorFactory.getInstance().getEditors(document);
      for (Editor editor : editors) {
        myCaretOffsets.put(editor, editor.getCaretModel().getOffset());
      }
    }

    public void update(@NotNull List<? extends TextChange> changes) {
      BulkChangesMerger merger = BulkChangesMerger.INSTANCE;
      for (Map.Entry<Editor, Integer> entry : myCaretOffsets.entrySet()) {
        entry.setValue(merger.updateOffset(entry.getValue(), changes));
      }
    }

    public void restoreCaretLocations() {
      for (Map.Entry<Editor, Integer> entry : myCaretOffsets.entrySet()) {
        entry.getKey().getCaretModel().moveToOffset(entry.getValue());
      }
    }
  }