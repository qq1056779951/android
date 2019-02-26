/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.tools.idea.databinding;

import com.android.tools.idea.res.DataBindingLayoutInfo;
import com.android.tools.idea.res.LocalResourceRepository;
import com.android.tools.idea.res.ResourceRepositoryManager;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.util.ArrayUtil;
import com.intellij.util.Function;
import com.intellij.util.Processor;
import com.intellij.util.containers.HashSet;
import java.lang.reflect.Array;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

/**
 * PsiShortNames cache that finds classes generated for layout files.
 */
public class DataBindingShortNamesCache extends PsiShortNamesCache {
  private final DataBindingProjectComponent myComponent;
  private final CachedValue<Map<String, List<DataBindingLayoutInfo>>> myNameCache;
  private final CachedValue<String[]> myAllClassNamesCache;
  private final CachedValue<Map<String, List<PsiMethod>>> myMethodsByNameCache;
  private final CachedValue<Map<String, List<PsiField>>> myFieldsByNameCache;
  private final CachedValue<String[]> myAllMethodNamesCache;
  private final CachedValue<String[]> myAllFieldNamesCache;

  public DataBindingShortNamesCache(DataBindingProjectComponent dataBindingProjectComponent) {
    myComponent = dataBindingProjectComponent;
    final NameCacheProvider nameCacheProvider = new NameCacheProvider(myComponent);
    Project project = dataBindingProjectComponent.getProject();
    CachedValuesManager cachedValuesManager = CachedValuesManager.getManager(project);
    myNameCache = cachedValuesManager.createCachedValue(nameCacheProvider, false);

    myAllClassNamesCache = cachedValuesManager.createCachedValue(
      () -> CachedValueProvider.Result.create(ArrayUtil.toStringArray(myNameCache.getValue().keySet()), nameCacheProvider), false);

    myMethodsByNameCache = cachedValuesManager.createCachedValue(() -> {
      final Map<String, List<PsiMethod>> result = Maps.newHashMap();
      traverseAllClasses(psiClass -> {
        for (PsiMethod method : psiClass.getMethods()) {
          List<PsiMethod> psiMethods = result.get(method.getName());
          if (psiMethods == null) {
            psiMethods = Lists.newArrayList();
            result.put(method.getName(), psiMethods);
          }
          psiMethods.add(method);
        }
        return null;
      });
      return CachedValueProvider.Result.create(result, nameCacheProvider);
    }, false);

    myFieldsByNameCache = cachedValuesManager.createCachedValue(() -> {
      final Map<String, List<PsiField>> result = Maps.newHashMap();
      traverseAllClasses(psiClass -> {
        for (PsiField field : psiClass.getFields()) {
          List<PsiField> psiFields = result.get(field.getName());
          if (psiFields == null) {
            psiFields = Lists.newArrayList();
            result.put(field.getName(), psiFields);
          }
          psiFields.add(field);
        }
        return null;
      });
      return CachedValueProvider.Result.create(result, nameCacheProvider);
    }, false);

    myAllMethodNamesCache = cachedValuesManager.createCachedValue(() -> {
      Set<String> names = myMethodsByNameCache.getValue().keySet();
      return CachedValueProvider.Result.create(ArrayUtil.toStringArray(names), nameCacheProvider);
    }, false);

    myAllFieldNamesCache = cachedValuesManager.createCachedValue(() -> {
      Set<String> names = myFieldsByNameCache.getValue().keySet();
      return CachedValueProvider.Result.create(ArrayUtil.toStringArray(names), nameCacheProvider);
    }, false);
  }

  private void traverseAllClasses(Function<PsiClass, Void> receiver) {
    for (List<DataBindingLayoutInfo> infoList : myNameCache.getValue().values()) {
      for (DataBindingLayoutInfo info : infoList) {
        PsiClass psiClass = DataBindingClassFactory.getOrCreatePsiClass(info);
        receiver.fun(psiClass);
      }
    }
  }

  @NotNull
  @Override
  public PsiClass[] getClassesByName(@NotNull @NonNls String name, @NotNull GlobalSearchScope scope) {
    if (!isEnabled()) {
      return PsiClass.EMPTY_ARRAY;
    }
    List<DataBindingLayoutInfo> infoList = myNameCache.getValue().get(name);
    if (infoList == null || infoList.isEmpty()) {
      return PsiClass.EMPTY_ARRAY;
    }
    List<PsiClass> selected = Lists.newArrayList();
    for (DataBindingLayoutInfo info : infoList) {
      if (scope.accept(info.getPsiFile().getVirtualFile())) {
        selected.add(DataBindingClassFactory.getOrCreatePsiClass(info));
      }
    }
    if (selected.isEmpty()) {
      return PsiClass.EMPTY_ARRAY;
    }
    return selected.toArray(PsiClass.EMPTY_ARRAY);
  }

  @NotNull
  @Override
  public String[] getAllClassNames() {
    if (!isEnabled()) {
      return ArrayUtil.EMPTY_STRING_ARRAY;
    }
    return myAllClassNamesCache.getValue();
  }

  @Override
  public void getAllClassNames(@NotNull HashSet<String> dest) {
    if (!isEnabled()) {
      return;
    }
    Collections.addAll(dest, getAllClassNames());
  }

  @NotNull
  @Override
  public PsiMethod[] getMethodsByName(@NonNls @NotNull String name, @NotNull GlobalSearchScope scope) {
    if (!isEnabled()) {
      return PsiMethod.EMPTY_ARRAY;
    }
    List<PsiMethod> methods = myMethodsByNameCache.getValue().get(name);
    return filterByScope(methods, scope, PsiMethod.class, PsiMethod.EMPTY_ARRAY);
  }

  @NotNull
  @Override
  public PsiMethod[] getMethodsByNameIfNotMoreThan(@NonNls @NotNull String name, @NotNull GlobalSearchScope scope, int maxCount) {
    if (!isEnabled()) {
      return PsiMethod.EMPTY_ARRAY;
    }
    PsiMethod[] methods = getMethodsByName(name, scope);
    if (methods.length > maxCount) {
      return PsiMethod.EMPTY_ARRAY;
    }
    return methods;
  }

  @NotNull
  @Override
  public PsiField[] getFieldsByNameIfNotMoreThan(@NonNls @NotNull String name, @NotNull GlobalSearchScope scope, int maxCount) {
    if (!isEnabled()) {
      return PsiField.EMPTY_ARRAY;
    }
    List<PsiField> psiFields = myFieldsByNameCache.getValue().get(name);
    PsiField[] selected = filterByScope(psiFields, scope, PsiField.class, PsiField.EMPTY_ARRAY);
    if (selected.length > maxCount) {
      return PsiField.EMPTY_ARRAY;
    }
    return selected;
  }

  private static <T extends PsiElement> T[] filterByScope(List<T> items, @NotNull GlobalSearchScope scope, Class<T> klass, T[] defaultValue) {
    if (items == null || items.isEmpty()) {
      return defaultValue;
    }
    List<T> selected = Lists.newArrayList();
    for (T item : items) {
      if (item.getContainingFile() != null && scope.accept(item.getContainingFile().getVirtualFile())) {
        selected.add(item);
      }
    }
    //noinspection unchecked
    return selected.toArray((T[])Array.newInstance(klass, selected.size()));
  }

  @Override
  public boolean processMethodsWithName(@NonNls @NotNull String name,
                                        @NotNull GlobalSearchScope scope,
                                        @NotNull Processor<PsiMethod> processor) {
    for (PsiMethod method : getMethodsByName(name, scope)) {
      if (!processor.process(method)) {
        return false;
      }
    }
    return true;
  }

  @NotNull
  @Override
  public String[] getAllMethodNames() {
    if (!isEnabled()) {
      return ArrayUtil.EMPTY_STRING_ARRAY;
    }
    return myAllMethodNamesCache.getValue();
  }

  @Override
  public void getAllMethodNames(@NotNull HashSet<String> set) {
    if (!isEnabled()) {
      return;
    }
    Collections.addAll(set, getAllClassNames());
  }

  @NotNull
  @Override
  public PsiField[] getFieldsByName(@NotNull @NonNls String name, @NotNull GlobalSearchScope scope) {
    if (!isEnabled()) {
      return PsiField.EMPTY_ARRAY;
    }
    List<PsiField> psiFields = myFieldsByNameCache.getValue().get(name);
    return filterByScope(psiFields, scope, PsiField.class, PsiField.EMPTY_ARRAY);
  }

  @NotNull
  @Override
  public String[] getAllFieldNames() {
    if (!isEnabled()) {
      return ArrayUtil.EMPTY_STRING_ARRAY;
    }
    return myAllFieldNamesCache.getValue();
  }

  @Override
  public void getAllFieldNames(@NotNull HashSet<String> set) {
    if (!isEnabled()) {
      return;
    }
    Collections.addAll(set, getAllFieldNames());
  }

  private static class NameCacheProvider extends ProjectResourceCachedValueProvider.MergedMapValueProvider<String, DataBindingLayoutInfo> {

    public NameCacheProvider(DataBindingProjectComponent component) {
      super(component);
    }

    @Override
    ResourceCacheValueProvider<Map<String, List<DataBindingLayoutInfo>>> createCacheProvider(AndroidFacet facet) {
      return new FacetNameCacheProvider(facet);
    }
  }

  private static class FacetNameCacheProvider extends ResourceCacheValueProvider<Map<String, List<DataBindingLayoutInfo>>> {
    public FacetNameCacheProvider(AndroidFacet facet) {
      super(facet, null);
    }

    @Override
    Map<String, List<DataBindingLayoutInfo>> doCompute() {
      LocalResourceRepository moduleResources = ResourceRepositoryManager.getInstance(getFacet()).getExistingModuleResources();
      if (moduleResources == null) {
        return defaultValue();
      }
      Map<String, DataBindingLayoutInfo> dataBindingResourceFiles = moduleResources.getDataBindingResourceFiles();
      if (dataBindingResourceFiles == null) {
        return defaultValue();
      }
      Map<String, List<DataBindingLayoutInfo>> cache = Maps.newHashMap();
      for (DataBindingLayoutInfo info : dataBindingResourceFiles.values()) {
        List<DataBindingLayoutInfo> infoList = cache.get(info.getClassName());
        if (infoList == null) {
          infoList = Lists.newArrayList();
          cache.put(info.getClassName(), infoList);
        }
        infoList.add(info);
      }
      return cache;
    }

    @Override
    Map<String, List<DataBindingLayoutInfo>> defaultValue() {
      return Maps.newHashMap();
    }
  }

  private boolean isEnabled() {
    return DataBindingCodeGenService.getInstance().isCodeGenSetToInMemoryFor(myComponent);
  }
}
