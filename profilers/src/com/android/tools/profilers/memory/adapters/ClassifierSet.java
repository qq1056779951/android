/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.tools.profilers.memory.adapters;

import com.android.annotations.VisibleForTesting;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A general base class for classifying/filtering objects into categories.
 */
public abstract class ClassifierSet implements MemoryObject {
  @NotNull private final String myName;

  @NotNull protected final ArrayList<InstanceObject> myInstances;

  // Lazily create the Classifier, as it is configurable and isn't necessary until nodes under this node needs to be classified.
  @Nullable protected Classifier myClassifier = null;

  protected int myAllocatedCount = 0;
  protected int myDeallocatedCount = 0;
  protected long myTotalShallowSize = 0L;
  protected long myTotalRetainedSize = 0L;
  protected int myInstancesWithStackInfoCount = 0;

  public ClassifierSet(@NotNull String name) {
    myName = name;
    myInstances = new ArrayList<>(0);
    resetDescendants();
  }

  @Override
  @NotNull
  public String getName() {
    return myName;
  }

  public int getAllocatedCount() {
    return myAllocatedCount;
  }

  public int getDeallocatedCount() {
    return myDeallocatedCount;
  }

  public long getTotalRetainedSize() {
    return myTotalRetainedSize;
  }

  public long getTotalShallowSize() {
    return myTotalShallowSize;
  }

  public void addInstanceObject(@NotNull InstanceObject instanceObject) {
    addInstanceObject(instanceObject, null);
  }

  public void addInstanceObject(@NotNull InstanceObject instanceObject, @Nullable List<ClassifierSet> pathResult) {
    if (pathResult != null) {
      pathResult.add(this);
    }

    if (myClassifier != null) {
      if (!myClassifier.classify(instanceObject, pathResult)) {
        myInstances.add(instanceObject);
      }
    }
    else {
      myInstances.add(instanceObject);
    }
    myAllocatedCount++;
    myTotalShallowSize += instanceObject.getShallowSize() == INVALID_VALUE ? 0 : instanceObject.getShallowSize();
    myTotalRetainedSize += instanceObject.getRetainedSize() == INVALID_VALUE ? 0 : instanceObject.getRetainedSize();
    myInstancesWithStackInfoCount +=
      (instanceObject.getCallStack() != null && instanceObject.getCallStack().getStackFramesCount() > 0) ? 1 : 0;
  }

  public void freeInstanceObject(@NotNull InstanceObject instanceObject, @Nullable List<ClassifierSet> pathResult) {
    if (pathResult != null) {
      pathResult.add(this);
    }

    if (myClassifier != null && !myClassifier.isTerminalClassifier()) {
      // TODO: ADD TO myInstances, and figure out how to not add duplicates
      myClassifier.getOrCreateClassifierSet(instanceObject).freeInstanceObject(instanceObject, pathResult);
    }

    myDeallocatedCount++;
    myTotalShallowSize -= instanceObject.getShallowSize() == INVALID_VALUE ? 0 : instanceObject.getShallowSize();
    myTotalRetainedSize -= instanceObject.getRetainedSize() == INVALID_VALUE ? 0 : instanceObject.getRetainedSize();
    myInstancesWithStackInfoCount -=
      (instanceObject.getCallStack() != null && instanceObject.getCallStack().getStackFramesCount() > 0) ? 1 : 0;
  }

  public int getInstancesCount() {
    if (myClassifier == null) {
      return myInstances.size();
    }
    else {
      return (int)getInstancesStream().count();
    }
  }

  /**
   * Gets a stream of all instances (including all descendants) in this ClassifierSet.
   */
  @NotNull
  public Stream<InstanceObject> getInstancesStream() {
    if (myClassifier == null) {
      return myInstances.stream();
    }
    else {
      return Stream.concat(getChildrenClassifierSets().stream().flatMap(ClassifierSet::getInstancesStream), myInstances.stream());
    }
  }

  public boolean hasStackInfo() {
    return myInstancesWithStackInfoCount > 0;
  }

  @NotNull
  public List<ClassifierSet> getChildrenClassifierSets() {
    ensurePartition();
    assert myClassifier != null;
    return myClassifier.getClassifierSets();
  }

  /**
   * O(N) search through all descendant ClassifierSet.
   *
   * @return the set that contains the {@code target}, or null otherwise.
   */
  @Nullable
  public ClassifierSet findContainingClassifierSet(@NotNull InstanceObject target) {
    boolean instancesContainsTarget = myInstances.contains(target);
    if (instancesContainsTarget && myClassifier != null) {
      return this;
    }
    else if (instancesContainsTarget || myClassifier != null) {
      List<ClassifierSet> childrenClassifierSets = getChildrenClassifierSets();
      if (instancesContainsTarget && myInstances.contains(target)) {
        return this; // If after the partition the target still falls within the instances within this set, then return this set.
      }
      for (ClassifierSet set : childrenClassifierSets) {
        ClassifierSet result = set.findContainingClassifierSet(target);
        if (result != null) {
          return result;
        }
      }
    }

    return null;
  }

  /**
   * Determines if {@code this} ClassifierSet's descendant children forms a superset (could be equivalent) of the given
   * {@code targetSet}'s immediate children.
   */
  public boolean isSupersetOf(@NotNull ClassifierSet targetSet) {
    // TODO perhaps not use getImmediateInstances if we want this to work across all inheritors of ClassifierSet?
    if (getInstancesCount() < targetSet.getInstancesCount()) {
      return false;
    }

    Set<InstanceObject> instances = getInstancesStream().collect(Collectors.toSet());
    return targetSet.getInstancesStream().allMatch(instances::contains);
  }

  protected void resetDescendants() {
    myInstances.clear();
    myInstances.trimToSize();
    myClassifier = null;
    myAllocatedCount = 0;
    myTotalShallowSize = 0;
    myTotalRetainedSize = 0;
    myInstancesWithStackInfoCount = 0;
  }

  /**
   * Force the instances of this node to be partitioned.
   */
  private void ensurePartition() {
    if (myClassifier == null) {
      myClassifier = createSubClassifier();
      myClassifier.partition(myInstances);
    }
  }

  /**
   * Gets the classifier this class will use to classify its instances.
   */
  @NotNull
  protected abstract Classifier createSubClassifier();

  /**
   * The base index for holding child {@link ClassifierSet}s.
   */
  @VisibleForTesting(visibility = VisibleForTesting.Visibility.PROTECTED)
  public static abstract class Classifier {
    public static final Classifier IDENTITY_CLASSIFIER = new Classifier() {
      @Override
      public boolean isTerminalClassifier() {
        return true;
      }

      @NotNull
      @Override
      public ClassifierSet getOrCreateClassifierSet(@NotNull InstanceObject instance) {
        throw new NotImplementedException(); // not used
      }

      @Override
      public boolean classify(@NotNull InstanceObject instance, @Nullable List<ClassifierSet> path) {
        return false;
      }

      @NotNull
      @Override
      public List<ClassifierSet> getClassifierSets() {
        return Collections.emptyList();
      }
    };

    /**
     * @return true if this Classifier is a terminal classifier, and instances will not be further classified.
     */
    public boolean isTerminalClassifier() {
      return false;
    }

    /**
     * Creates a partition for the given {@code instance}, if none exists, or returns an appropriate existing one.
     */
    @NotNull
    public abstract ClassifierSet getOrCreateClassifierSet(@NotNull InstanceObject instance);

    /**
     * Gets a {@link List} of the child ClassifierSets.
     */
    @NotNull
    public abstract List<ClassifierSet> getClassifierSets();

    /**
     * Classifies a single instance for this Classifier.
     */
    // TODO if sorting is specified, we need to sort again
    public boolean classify(@NotNull InstanceObject instance, @Nullable List<ClassifierSet> path) {
      getOrCreateClassifierSet(instance).addInstanceObject(instance, path);
      return true;
    }

    /**
     * Partitions {@link InstanceObject}s in {@code myInstances} according to the current {@link ClassifierSet}'s strategy.
     * This will consume the instance from the input.
     */
    public final void partition(@NotNull ArrayList<InstanceObject> instances) {
      List<InstanceObject> partitionedInstances = new ArrayList<>(instances.size());

      instances.forEach(instance -> {
        if (classify(instance, null)) {
          partitionedInstances.add(instance);
        }
      });

      if (partitionedInstances.size() == instances.size()) {
        instances.clear();
      }
      else {
        instances.removeAll(partitionedInstances);
      }
      instances.trimToSize();
    }
  }
}
