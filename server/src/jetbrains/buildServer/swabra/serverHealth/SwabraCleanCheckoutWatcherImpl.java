/*
 * Copyright 2000-2019 JetBrains s.r.o.
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

package jetbrains.buildServer.swabra.serverHealth;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import jetbrains.buildServer.serverSide.*;
import jetbrains.buildServer.serverSide.cleanup.CleanupExtension;
import jetbrains.buildServer.serverSide.cleanup.CleanupExtensionAdapter;
import jetbrains.buildServer.serverSide.cleanup.CleanupProcessState;
import jetbrains.buildServer.swabra.SwabraUtil;
import jetbrains.buildServer.util.EventDispatcher;
import jetbrains.buildServer.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * @author vbedrosova
 */
public class SwabraCleanCheckoutWatcherImpl implements SwabraCleanCheckoutWatcher {

  public static final String CLEAN_CHECKOUT_BUILDS_STORAGE = "swabra.clean.checkout.builds.storage";
  public static final String BUILDS_STORAGE_PERIOD_PROPERTY = "teamcity.healthStatus.swabra.clean.checkout.builds.storage.period";

  public static final long MONTH = 30*24*3600*1000L;

  @NotNull private final ProjectManager myProjectManager;
  @NotNull private final ServerResponsibility myServerResponsibility;

  public SwabraCleanCheckoutWatcherImpl(@NotNull EventDispatcher<BuildServerListener> eventDispatcher,
                                        @NotNull ProjectManager projectManager,
                                        @NotNull final ServerExtensionHolder extensionHolder,
                                        @NotNull ServerResponsibility serverResponsibility) {
    myProjectManager = projectManager;
    myServerResponsibility = serverResponsibility;

    eventDispatcher.addListener(new BuildServerAdapter(){
      @Override
      public void buildFinished(@NotNull final SRunningBuild build) {
        onBuildFinished(build);
      }
    });

    extensionHolder.registerExtension(CleanupExtension.class, SwabraCleanCheckoutWatcherImpl.class.getName(), new CleanupExtensionAdapter() {
      @Override
      public void afterCleanup(@NotNull final CleanupProcessState cleanupState) {
        cleanOldValues(cleanupState);
      }
    });
  }


  private void onBuildFinished(@NotNull final SRunningBuild build) {
    if (!myServerResponsibility.canManageProjectsConfigs())
      return;

    final SBuildType buildType = build.getBuildType();
    if (buildType == null) return;

    final String causeBuildTypeId = build.getParametersProvider().get(SwabraUtil.CLEAN_CHECKOUT_CAUSE_BUILD_TYPE_ID);
    if (StringUtil.isNotEmpty(causeBuildTypeId)) {
      getDataStorage(buildType).putValue(causeBuildTypeId, String.valueOf(System.currentTimeMillis()));
    }
  }

  @NotNull
  private CustomDataStorage getDataStorage(final SBuildType buildType) {
    return buildType.getCustomDataStorage(CLEAN_CHECKOUT_BUILDS_STORAGE);
  }

  private void cleanOldValues(@NotNull final CleanupProcessState cleanupState) {

    final long now = System.currentTimeMillis();

    for (SBuildType bt : myProjectManager.getActiveBuildTypes()) {
      if (cleanupState.isInterrupted())
        return;

      final CustomDataStorage storage = getDataStorage(bt);
      final Map<String, String> values = storage.getValues();
      if (values == null) continue;

      for (Map.Entry<String, String> e : values.entrySet()) {
        if (cleanupState.isInterrupted())
          return;
        if (isOldOrBad(e.getValue(), now) || myProjectManager.findBuildTypeById(e.getKey()) == null) {
          storage.putValue(e.getKey(), null);
        }
      }

    }
  }

  private static boolean isOldOrBad(@Nullable String timestamp, long now) {
    try {
      return timestamp == null || now - Long.parseLong(timestamp) > TeamCityProperties.getLong(BUILDS_STORAGE_PERIOD_PROPERTY, MONTH);
    } catch (NumberFormatException e) {
      return true;
    }
  }

  // returns ids of the build types which recently caused swabra clean checkout for the provided build type
  // see teamcity.healthStatus.swabra.builds.storage.period property
  @NotNull
  public Collection<String> getRecentCleanCheckoutCauses(@NotNull SBuildType buildType) {
    final Map<String, String> values = buildType.getCustomDataStorage(CLEAN_CHECKOUT_BUILDS_STORAGE).getValues();
    return values == null ? Collections.emptyList() : values.keySet();
  }
}
