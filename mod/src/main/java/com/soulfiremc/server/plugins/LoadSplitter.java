/*
 * SoulFire
 * Copyright (C) 2026  AlexProgrammerDE
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.soulfiremc.server.plugins;

import com.soulfiremc.server.api.InternalPlugin;
import com.soulfiremc.server.api.InternalPluginClass;
import com.soulfiremc.server.api.PluginInfo;
import com.soulfiremc.server.api.event.lifecycle.InstanceSettingsRegistryInitEvent;
import com.soulfiremc.server.settings.lib.SettingsObject;
import com.soulfiremc.server.settings.lib.SettingsSource;
import com.soulfiremc.server.settings.property.BooleanProperty;
import com.soulfiremc.server.settings.property.ImmutableBooleanProperty;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import net.lenni0451.lambdaevents.EventHandler;

/// Load Splitter Plugin (Skeleton)
/// This plugin currently serves as a placeholder for future load balancing features.
/// It appears in the plugin list but performs no active logic in this version.
@InternalPluginClass
public final class LoadSplitter extends InternalPlugin {

  public LoadSplitter() {
    super(new PluginInfo(
        "load-splitter",
        "1.1.0",
        "Splits bot load across multiple remote SoulFire servers for distributed processing",
        "At0Mic_X",
        "AGPL-3.0",
        "https://soulfiremc.com"));
  }

  @EventHandler
  public void onSettingsRegistryInit(InstanceSettingsRegistryInitEvent event) {
    event.settingsPageRegistry().addPluginPage(
        LoadSplitterSettings.class,
        "load-splitter",
        "Load Splitter (Beta)",
        this,
        "split",
        LoadSplitterSettings.ENABLED);
  }

  @NoArgsConstructor(access = AccessLevel.PRIVATE)
  public static class LoadSplitterSettings implements SettingsObject {
    private static final String NAMESPACE = "load-splitter";

    public static final BooleanProperty<SettingsSource.Instance> ENABLED =
        ImmutableBooleanProperty.<SettingsSource.Instance>builder()
            .sourceType(SettingsSource.Instance.INSTANCE)
            .namespace(NAMESPACE)
            .key("enabled")
            .uiName("Enable Load Splitter")
            .description("Enable experimental load balancing. (Feature currently in Beta/Disabled)")
            .defaultValue(false)
            .build();
  }
}
