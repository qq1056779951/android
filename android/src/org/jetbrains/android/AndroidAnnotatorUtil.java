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
package org.jetbrains.android;

import static com.android.SdkConstants.ANDROID_URI;
import static com.android.SdkConstants.ATTR_DRAWABLE;
import static com.android.SdkConstants.ATTR_SRC;
import static com.android.SdkConstants.DOT_XML;
import static com.android.SdkConstants.FD_RES_LAYOUT;

import com.android.ide.common.rendering.api.ResourceReference;
import com.android.ide.common.rendering.api.ResourceValue;
import com.android.ide.common.resources.ResourceItem;
import com.android.ide.common.resources.ResourceResolver;
import com.android.ide.common.resources.configuration.DensityQualifier;
import com.android.ide.common.resources.configuration.FolderConfiguration;
import com.android.resources.Density;
import com.android.tools.idea.configurations.Configuration;
import com.android.tools.idea.configurations.ConfigurationManager;
import com.android.tools.idea.res.FileResourceReader;
import com.android.tools.idea.res.IdeResourcesUtil;
import com.android.tools.idea.res.LocalResourceRepository;
import com.android.tools.idea.res.ResourceRepositoryManager;
import com.android.tools.idea.ui.resourcechooser.ColorResourcePicker;
import com.android.tools.idea.ui.resourcechooser.HorizontalTabbedPanelBuilder;
import com.android.tools.idea.ui.resourcechooser.colorpicker2.ColorPickerBuilder;
import com.android.tools.idea.ui.resourcechooser.colorpicker2.internal.MaterialColorPaletteProvider;
import com.android.tools.idea.ui.resourcechooser.colorpicker2.internal.MaterialGraphicalColorPipetteProvider;
import com.android.tools.idea.ui.resourcechooser.util.ResourceChooserHelperKt;
import com.android.tools.idea.ui.resourcemanager.rendering.ColorIconProvider;
import com.android.tools.idea.ui.resourcemanager.rendering.MultipleColorIcon;
import com.android.utils.HashCodes;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.TransactionGuard;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.UnknownFileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlTagValue;
import com.intellij.ui.scale.JBUIScale;
import com.intellij.util.Consumer;
import com.intellij.util.ui.ColorIcon;
import com.intellij.util.ui.EmptyIcon;
import java.awt.*;
import java.util.List;
import java.util.Objects;
import javax.swing.*;
import com.intellij.util.ui.JBScalableIcon;
import com.intellij.util.ui.JBUI;
import java.awt.Color;
import java.awt.MouseInfo;
import java.util.List;
import java.util.Objects;
import javax.swing.Icon;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.xmlpull.v1.XmlPullParser;

/**
 * Static methods to be used by Android annotators.
 */
public class AndroidAnnotatorUtil {
  static final int MAX_ICON_SIZE = 5000;
  private static final String SET_COLOR_COMMAND_NAME = "Change Color";
  private static final int ICON_SIZE = 8;

  /**
   * Returns a bitmap to be used as an icon to annotate an Android resource reference in an XML file.
   *
   * @param resourceValue the resource value defining the resource being referenced
   * @param resourceResolver the resource resolver to use
   * @param facet the android facet
   * @return the bitmap for the annotation icon, or null to have no annotation icon
   */
  @Nullable
  public static VirtualFile resolveDrawableFile(@NotNull ResourceValue resourceValue,
                                                @NotNull ResourceResolver resourceResolver,
                                                @NotNull AndroidFacet facet) {
    Project project = facet.getModule().getProject();
    VirtualFile file = IdeResourcesUtil.resolveDrawable(resourceResolver, resourceValue, project);
    if (file != null && file.getPath().endsWith(DOT_XML)) {
      file = pickBitmapFromXml(file, resourceResolver, project, facet, resourceValue);
    }
    return pickBestBitmap(file);
  }

  @Nullable
  private static VirtualFile pickBitmapFromXml(@NotNull VirtualFile file,
                                               @NotNull ResourceResolver resourceResolver,
                                               @NotNull Project project,
                                               @NotNull AndroidFacet facet,
                                               @NotNull ResourceValue resourceValue) {
    try {
      XmlPullParser parser = FileResourceReader.createXmlPullParser(file);
      if (parser == null) {
        return null;
      }
      if (parser.nextTag() != XmlPullParser.START_TAG) {
        return null;
      }

      String source;
      String tagName = parser.getName();

      switch (tagName) {
        case "vector": {
          // Take a look and see if we have a bitmap we can fall back to.
          LocalResourceRepository resourceRepository = ResourceRepositoryManager.getAppResources(facet);
          List<ResourceItem> items =
              resourceRepository.getResources(resourceValue.getNamespace(), resourceValue.getResourceType(), resourceValue.getName());
          for (ResourceItem item : items) {
            FolderConfiguration configuration = item.getConfiguration();
            DensityQualifier densityQualifier = configuration.getDensityQualifier();
            if (densityQualifier != null) {
              Density density = densityQualifier.getValue();
              if (density != null && density.isValidValueForDevice()) {
                return IdeResourcesUtil.getSourceAsVirtualFile(item);
              }
            }
          }
          // Vectors are handled in the icon cache.
          return file;
        }

        case "bitmap":
        case "nine-patch":
          source = parser.getAttributeValue(ANDROID_URI, ATTR_SRC);
          break;

        case "clip":
        case "inset":
        case "scale":
          source = parser.getAttributeValue(ANDROID_URI, ATTR_DRAWABLE);
          break;

        case "layer-list":
        case "level-list":
        case "selector":
        case "shape":
        case "transition":
          return file;

        default:
          // <set>, <drawable> etc - no bitmap to be found.
          return null;
      }
      if (source == null) {
        return null;
      }
      ResourceValue resValue = resourceResolver.findResValue(source, resourceValue.isFramework());
      return resValue == null ? null : IdeResourcesUtil.resolveDrawable(resourceResolver, resValue, project);
    }
    catch (Throwable ignore) {
      // Not logging for now; afraid to risk unexpected crashes in upcoming preview. TODO: Re-enable.
      //Logger.getInstance(AndroidColorAnnotator.class).warn(String.format("Could not read/render icon image %1$s", file), e);
      return null;
    }
  }

  @Nullable
  public static VirtualFile pickBestBitmap(@Nullable VirtualFile bitmap) {
    if (bitmap != null && bitmap.exists()) {
      // Pick the smallest resolution, if possible! E.g. if the theme resolver located
      // drawable-hdpi/foo.png, and drawable-mdpi/foo.png pick that one instead (and ditto
      // for -ldpi etc)
      VirtualFile smallest = findSmallestDpiVersion(bitmap);
      if (smallest != null) {
        return smallest;
      }

      // TODO: For XML drawables, look in the rendered output to see if there's a DPI version we can use:
      // These are found in  ${module}/build/generated/res/pngs/debug/drawable-*dpi

      long length = bitmap.getLength();
      if (length < MAX_ICON_SIZE) {
        return bitmap;
      }
    }

    return null;
  }

  @Nullable
  private static VirtualFile findSmallestDpiVersion(@NotNull VirtualFile bitmap) {
    VirtualFile parentFile = bitmap.getParent();
    if (parentFile == null) {
      return null;
    }
    VirtualFile resFolder = parentFile.getParent();
    if (resFolder == null) {
      return null;
    }
    String parentName = parentFile.getName();
    FolderConfiguration config = FolderConfiguration.getConfigForFolder(parentName);
    if (config == null) {
      return null;
    }
    DensityQualifier qualifier = config.getDensityQualifier();
    if (qualifier == null) {
      return null;
    }
    Density density = qualifier.getValue();
    if (density != null && density.isValidValueForDevice()) {
      String fileName = bitmap.getName();
      Density[] densities = Density.values();
      // Iterate in reverse, since the Density enum is in descending order.
      for (int i = densities.length; --i >= 0;) {
        Density d = densities[i];
        if (d.isValidValueForDevice()) {
          String folderName = parentName.replace(density.getResourceValue(), d.getResourceValue());
          VirtualFile folder = resFolder.findChild(folderName);
          if (folder != null) {
            bitmap = folder.findChild(fileName);
            if (bitmap != null) {
              if (bitmap.getLength() > MAX_ICON_SIZE) {
                // No point continuing the loop; the other densities will be too big too.
                return null;
              }
              return bitmap;
            }
          }
        }
      }
    }

    return null;
  }

  /**
   * Picks a suitable configuration to use for resource resolution within a given file.
   *
   * @param file the file to determine a configuration for
   * @param facet {@link AndroidFacet} of the {@code file}
   */
  @Nullable
  public static Configuration pickConfiguration(@NotNull PsiFile file, @NotNull AndroidFacet facet) {
    VirtualFile virtualFile = file.getVirtualFile();
    if (virtualFile == null) {
      return null;
    }

    VirtualFile parent = virtualFile.getParent();
    if (parent == null) {
      return null;
    }
    VirtualFile layout;
    String parentName = parent.getName();
    if (!parentName.startsWith(FD_RES_LAYOUT)) {
      layout = IdeResourcesUtil.pickAnyLayoutFile(facet);
      if (layout == null) {
        return null;
      }
    } else {
      layout = virtualFile;
    }

    return ConfigurationManager.getOrCreateInstance(facet.getModule()).getConfiguration(layout);
  }


  /**
   * Return {@link FileType} if found, or {@link UnknownFileType#INSTANCE} otherwise.
   */
  @NotNull
  public static FileType getFileType(@NotNull PsiElement element) {
    return ApplicationManager.getApplication().runReadAction((Computable<FileType>)() -> {
      PsiFile file = element.getContainingFile();
      if (file != null) {
        return file.getFileType();
      }
      return UnknownFileType.INSTANCE;
    });
  }

  public static class ColorRenderer extends GutterIconRenderer {
    @NotNull private final PsiElement myElement;
    @Nullable private final Color myColor;
    @NotNull private final ResourceResolver myResolver;
    @Nullable private final ResourceReference myResourceReference;
    private final Consumer<String> mySetColorTask;
    private final boolean myIncludeClickAction;
    @Nullable private final Configuration myConfiguration;

    public ColorRenderer(@NotNull PsiElement element,
                         @Nullable Color color,
                         @NotNull ResourceResolver resolver,
                         @Nullable ResourceReference resourceReference,
                         boolean includeClickAction,
                         @Nullable Configuration configuration) {
      myElement = element;
      myColor = color;
      myResolver = resolver;
      myResourceReference = resourceReference;
      myIncludeClickAction = includeClickAction;
      mySetColorTask = createSetAttributeTask(myElement);
      myConfiguration = configuration;
    }

    @NotNull
    @Override
    public Icon getIcon() {
      if (myResourceReference != null) {
        AndroidFacet facet = AndroidFacet.getInstance(myElement);
        if (facet != null) {
          ResourceValue value = myResolver.getUnresolvedResource(myResourceReference);
          List<Color> colors = IdeResourcesUtil.resolveMultipleColors(myResolver, value, myElement.getProject());
          if (!colors.isEmpty()) {
            MultipleColorIcon icon = new MultipleColorIcon();
            icon.setColors(colors);
            int scaledIconSize = JBUIScale.scale(ICON_SIZE);
            icon.setWidth(scaledIconSize);
            icon.setHeight(scaledIconSize);
            return icon;
          }
          return JBUIScale.scaleIcon(EmptyIcon.create(ICON_SIZE));
        }
      }

      Color color = getCurrentColor();
      return color == null ? JBUIScale.scaleIcon(EmptyIcon.create(ICON_SIZE)) : JBUIScale.scaleIcon(new ColorIcon(ICON_SIZE, color));
    }

    @Nullable
    private Color getCurrentColor() {
      if (myColor != null) {
        return myColor;
      } else if (myElement instanceof XmlTag) {
        return IdeResourcesUtil.parseColor(((XmlTag)myElement).getValue().getText());
      } else if (myElement instanceof XmlAttributeValue) {
        return IdeResourcesUtil.parseColor(((XmlAttributeValue)myElement).getValue());
      } else {
        return null;
      }
    }

    @Override
    public AnAction getClickAction() {
      if (!myIncludeClickAction) { // Cannot set colors that were derived.
        return null;
      }
      return new AnAction() {
        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
          Editor editor = e.getData(CommonDataKeys.EDITOR);
          if (editor != null) {
            openColorPicker(getCurrentColor());
          }
        }
      };
    }

    private void openColorPicker(@Nullable Color currentColor) {
      // TODO: When the color is color state, open color picker with resource tab and select it.
      ResourceChooserHelperKt.createAndShowColorPickerPopup(
        currentColor,
        myResourceReference,
        myConfiguration,
        null,
        MouseInfo.getPointerInfo().getLocation(),
        color -> {
          setColorToAttribute(color);
          return null;
        },
        resourceString -> {
          setColorStringAttribute(resourceString);
          return null;
        });
    }

    private void setColorToAttribute(@NotNull Color color) {
      setColorStringAttribute(IdeResourcesUtil.colorToString(color));
    }

    private void setColorStringAttribute(@NotNull String colorString) {
      Project project = myElement.getProject();
      TransactionGuard.submitTransaction(project, () ->
        WriteCommandAction.runWriteCommandAction(project, SET_COLOR_COMMAND_NAME, null, () -> mySetColorTask.consume(colorString))
      );
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      ColorRenderer that = (ColorRenderer)o;
      // TODO: Compare with modification count in app resources (if not framework).
      if (!Objects.equals(myColor, that.myColor)) return false;
      if (!myElement.equals(that.myElement)) return false;

      return true;
    }

    @Override
    public int hashCode() {
      return HashCodes.mix(myElement.hashCode(), Objects.hashCode(myColor));
    }
  }

  /**
   * Returns a {@link Consumer} that sets the value of an {@link XmlAttribute} or an {@link XmlTag}.
   */
  public static Consumer<String> createSetAttributeTask(@NotNull PsiElement psiElement) {
    SmartPsiElementPointer<PsiElement> smartPsiElementPointer = ReadAction.compute(() -> SmartPointerManager.createPointer(psiElement));
    return attributeValue -> {
      PsiElement element = smartPsiElementPointer.getElement();
      if (element instanceof XmlTag) {
        XmlTagValue xmlTagValue = ((XmlTag)element).getValue();
        xmlTagValue.setText(attributeValue);
      }
      else if (element instanceof XmlAttributeValue) {
        XmlAttribute xmlAttribute = PsiTreeUtil.getParentOfType(element, XmlAttribute.class);
        if (xmlAttribute != null) {
          xmlAttribute.setValue(attributeValue);
        }
      }
    };
  }
}
