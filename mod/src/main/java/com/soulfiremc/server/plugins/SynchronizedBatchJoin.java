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

import com.soulfiremc.server.InstanceManager;
import com.soulfiremc.server.api.InternalPlugin;
import com.soulfiremc.server.api.InternalPluginClass;
import com.soulfiremc.server.api.PluginInfo;
import com.soulfiremc.server.api.event.lifecycle.InstanceSettingsRegistryInitEvent;
import com.soulfiremc.server.api.event.session.SessionEndedEvent;
import com.soulfiremc.server.api.event.session.SessionStartEvent;
import com.soulfiremc.server.api.metadata.MetadataKey;
import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.server.settings.instance.BotSettings;
import com.soulfiremc.server.settings.lib.SettingsObject;
import com.soulfiremc.server.settings.lib.SettingsSource;
import com.soulfiremc.server.settings.property.*;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.lenni0451.lambdaevents.EventHandler;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/// Plugin that coordinates bots to wait at a gate until a threshold is met.
/// When enabled, bots will wait after connecting until a configured percentage
/// of bots have reached the gate, then all proceed together.
/// This is useful for coordinating captcha solving - all bots solve captcha,
/// then once threshold is met, all start their tasks simultaneously.
@Slf4j
@InternalPluginClass
public final class SynchronizedBatchJoin extends InternalPlugin {
    // Track batch state per instance
    private static final MetadataKey<BatchJoinState> BATCH_STATE = MetadataKey.of("synchronized-batch-join", "state",
            BatchJoinState.class);

    public SynchronizedBatchJoin() {
        super(new PluginInfo(
                "synchronized-batch-join",
                "1.0.0",
                "Coordinate bots to start tasks together after a threshold is met",
                "At0Mic_X",
                "AGPL-3.0",
                "https://soulfiremc.com"));
    }

    @EventHandler
    public void onSettingsRegistryInit(InstanceSettingsRegistryInitEvent event) {
        event.settingsPageRegistry().addPluginPage(
                SyncBatchSettings.class,
                "synchronized-batch-join",
                "Synchronized Batch Join",
                this,
                "users",
                SyncBatchSettings.ENABLED);
    }

    @EventHandler
    public void onSessionStart(SessionStartEvent event) {
        var instanceManager = event.instanceManager();
        var settingsSource = instanceManager.settingsSource();

        if (!settingsSource.get(SyncBatchSettings.ENABLED)) {
            return;
        }

        var expectedBots = settingsSource.get(BotSettings.AMOUNT);
        var thresholdPercent = settingsSource.get(SyncBatchSettings.READY_THRESHOLD);
        var requiredBots = (int) Math.ceil(expectedBots * thresholdPercent / 100.0);

        log.info("Batch join enabled: waiting for {} of {} bots ({}%) to be ready",
                requiredBots, expectedBots, thresholdPercent);

        instanceManager.metadata().set(BATCH_STATE,
                new BatchJoinState(expectedBots, requiredBots));
    }

    @EventHandler
    public void onSessionEnd(SessionEndedEvent event) {
        var state = event.instanceManager().metadata().get(BATCH_STATE);
        if (state != null) {
            // Release any waiting bots
            state.gateOpened.set(true);
            state.releaseLatch.countDown();
        }
        event.instanceManager().metadata().remove(BATCH_STATE);
    }

    /// Call this method when a bot has completed its preparation (e.g., solved
    /// captcha).
    /// This marks the bot as ready and checks if the threshold is met to open the
    /// gate.
    ///
    /// @param bot The bot connection that is ready
    public static void markBotReady(BotConnection bot) {
        var instanceManager = bot.instanceManager();
        var state = instanceManager.metadata().get(BATCH_STATE);

        if (state == null) {
            // Batch join not enabled
            return;
        }

        state.readyBots.add(bot.accountProfileId());
        var readyCount = state.readyBots.size();

        log.info("Bot {} is ready ({}/{} ready, need {})",
                bot.accountName(), readyCount, state.expectedBots, state.requiredBots);

        // Check if threshold met
        if (readyCount >= state.requiredBots && state.gateOpened.compareAndSet(false, true)) {
            log.info("🚀 Batch threshold met! Unleashing {} bots!", readyCount);
            state.releaseLatch.countDown();
        }
    }

    /// Check if a bot is ready (has been marked via markBotReady).
    ///
    /// @param bot The bot connection to check
    /// @return true if the bot has been marked as ready
    public static boolean isBotReady(BotConnection bot) {
        var state = bot.instanceManager().metadata().get(BATCH_STATE);
        if (state == null) {
            return true; // No sync configured, treat as ready
        }
        return state.readyBots.contains(bot.accountProfileId());
    }

    /// Wait for the batch gate to open.
    /// This blocks until either: the threshold is met, the timeout expires, or the
    /// session ends.
    ///
    /// @param bot The bot connection waiting
    /// @param timeoutSeconds Maximum time to wait in seconds
    /// @return true if the gate opened, false if timeout or interrupted
    public static boolean waitForBatchGate(BotConnection bot, long timeoutSeconds) {
        var state = bot.instanceManager().metadata().get(BATCH_STATE);

        if (state == null || state.gateOpened.get()) {
            return true; // No sync or already open
        }

        try {
            log.debug("Bot {} waiting at batch gate...", bot.accountName());
            return state.releaseLatch.await(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /// Check if batch join is enabled for an instance.
    ///
    /// @param instanceManager The instance manager
    /// @return true if batch join is enabled
    public static boolean isEnabled(InstanceManager instanceManager) {
        return instanceManager.metadata().get(BATCH_STATE) != null;
    }

    /// Check if the batch gate has been opened.
    ///
    /// @param instanceManager The instance manager
    /// @return true if the gate is open (threshold met or not enabled)
    public static boolean isGateOpen(InstanceManager instanceManager) {
        var state = instanceManager.metadata().get(BATCH_STATE);
        return state == null || state.gateOpened.get();
    }

    /// Get the current ready count for an instance.
    ///
    /// @param instanceManager The instance manager
    /// @return The number of ready bots, or 0 if batch join not enabled
    public static int getReadyCount(InstanceManager instanceManager) {
        var state = instanceManager.metadata().get(BATCH_STATE);
        return state == null ? 0 : state.readyBots.size();
    }

    /// State class to track batch coordination
    private static class BatchJoinState {
        final int expectedBots;
        final int requiredBots;
        final Set<UUID> readyBots = ConcurrentHashMap.newKeySet();
        final AtomicBoolean gateOpened = new AtomicBoolean(false);
        final CountDownLatch releaseLatch = new CountDownLatch(1);

        BatchJoinState(int expectedBots, int requiredBots) {
            this.expectedBots = expectedBots;
            this.requiredBots = requiredBots;
        }
    }

    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    private static class SyncBatchSettings implements SettingsObject {
        private static final String NAMESPACE = "synchronized-batch-join";

        public static final BooleanProperty<SettingsSource.Instance> ENABLED = ImmutableBooleanProperty.<SettingsSource.Instance>builder()
                .sourceType(SettingsSource.Instance.INSTANCE)
                .namespace(NAMESPACE)
                .key("enabled")
                .uiName("Enable Synchronized Batch Join")
                .description("Wait for a threshold of bots to be ready before all start tasks")
                .defaultValue(false)
                .build();

        public static final IntProperty<SettingsSource.Instance> READY_THRESHOLD = ImmutableIntProperty.<SettingsSource.Instance>builder()
                .sourceType(SettingsSource.Instance.INSTANCE)
                .namespace(NAMESPACE)
                .key("ready-threshold")
                .uiName("Ready Threshold (%)")
                .description("Percentage of bots that must be ready before all are unleashed")
                .defaultValue(60)
                .minValue(1)
                .maxValue(100)
                .stepValue(5)
                .build();

        public static final IntProperty<SettingsSource.Instance> GATE_TIMEOUT = ImmutableIntProperty.<SettingsSource.Instance>builder()
                .sourceType(SettingsSource.Instance.INSTANCE)
                .namespace(NAMESPACE)
                .key("gate-timeout")
                .uiName("Gate Timeout (seconds)")
                .description("Maximum time bots wait at gate before proceeding anyway")
                .defaultValue(300)
                .minValue(30)
                .maxValue(3600)
                .stepValue(30)
                .build();
    }
}
