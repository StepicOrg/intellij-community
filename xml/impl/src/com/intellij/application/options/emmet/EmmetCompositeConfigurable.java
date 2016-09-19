/*
 * Copyright 2000-2016 JetBrains s.r.o.
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
package com.intellij.application.options.emmet;

import com.intellij.codeInsight.template.impl.TemplateExpandShortcutPanel;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.options.UnnamedConfigurable;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.xml.XmlBundle;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.Collection;
import java.util.Collections;

public class EmmetCompositeConfigurable extends SearchableConfigurable.Parent.Abstract {
  private final Configurable[] myNestedConfigurables;
  @NotNull private final Configurable[] myInnerConfigurables;
  private TemplateExpandShortcutPanel myTemplateExpandShortcutPanel;
  
  public EmmetCompositeConfigurable(@NotNull Configurable... innerConfigurables) {
    this(Collections.<Configurable>emptyList(), innerConfigurables);
  }

  public EmmetCompositeConfigurable(Collection<Configurable> nestedConfigurables, @NotNull Configurable... innerConfigurables) {
    myNestedConfigurables = nestedConfigurables.toArray(new Configurable[nestedConfigurables.size()]);
    myInnerConfigurables = innerConfigurables;
    myTemplateExpandShortcutPanel = new TemplateExpandShortcutPanel(XmlBundle.message("emmet.expand.abbreviation.with"));
  }

  @Nls
  @Override
  public String getDisplayName() {
    return XmlBundle.message("emmet.configuration.title");
  }

  @Nullable
  @Override
  public String getHelpTopic() {
    return getId();
  }

  @Nullable
  @Override
  public JComponent createComponent() { 
    final JPanel rootPanel = new JPanel(new GridLayoutManager(myInnerConfigurables.length + 1, 1, new Insets(0, 0, 0, 0), -1, -1, false, false));
    rootPanel.add(myTemplateExpandShortcutPanel, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_NORTH,
                                                                     GridConstraints.FILL_HORIZONTAL,
                                                                     GridConstraints.SIZEPOLICY_CAN_GROW |
                                                                     GridConstraints.SIZEPOLICY_CAN_SHRINK,
                                                                     GridConstraints.SIZEPOLICY_FIXED, null, null, null));
    for (int i = 0; i < myInnerConfigurables.length; i++) {
      UnnamedConfigurable configurable = myInnerConfigurables[i];
      final JComponent component = configurable.createComponent();
      assert component != null;
      int vSizePolicy = GridConstraints.SIZEPOLICY_CAN_GROW | GridConstraints.SIZEPOLICY_CAN_SHRINK;
      if (i + 1 == myInnerConfigurables.length) {
        vSizePolicy |= GridConstraints.SIZEPOLICY_WANT_GROW;
      }
      rootPanel.add(component, new GridConstraints(i + 1, 0, 1, 1, GridConstraints.ANCHOR_NORTHWEST, GridConstraints.FILL_BOTH,
                                                   GridConstraints.SIZEPOLICY_CAN_GROW | GridConstraints.SIZEPOLICY_WANT_GROW |
                                                   GridConstraints.SIZEPOLICY_CAN_SHRINK,
                                                   vSizePolicy, null, null, null));
    }
    rootPanel.revalidate();
    return rootPanel;
  }

  @Override
  public void reset() {
    myTemplateExpandShortcutPanel.setSelectedChar((char)EmmetOptions.getInstance().getEmmetExpandShortcut());
    for (Configurable configurable : myInnerConfigurables) {
      configurable.reset();
    }
    super.reset();
  }

  @Override
  public void apply() throws ConfigurationException {
    EmmetOptions.getInstance().setEmmetExpandShortcut(myTemplateExpandShortcutPanel.getSelectedChar());
    for (Configurable configurable : myInnerConfigurables) {
      configurable.reset();
    }
    super.apply();
  }

  @Override
  public boolean isModified() {
    if (EmmetOptions.getInstance().getEmmetExpandShortcut() != myTemplateExpandShortcutPanel.getSelectedChar() || super.isModified()) {
      return true;
    }
    for (Configurable configurable : myInnerConfigurables) {
      if (configurable.isModified()) {
        return true;
      }
    }
    return false;
  }

  @Override
  public void disposeUIResources() {
    myTemplateExpandShortcutPanel = null;
    super.disposeUIResources();
  }

  @NotNull
  @Override
  public String getId() {
    return "reference.idesettings.emmet";
  }

  @Override
  public boolean hasOwnContent() {
    return true;
  }

  @Override
  protected Configurable[] buildConfigurables() {
    return myNestedConfigurables;
  }
}
