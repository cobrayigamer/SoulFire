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
import com.soulfiremc.server.api.event.bot.BotDisconnectedEvent;
import com.soulfiremc.server.api.event.lifecycle.InstanceSettingsRegistryInitEvent;
import com.soulfiremc.server.api.metadata.MetadataKey;
import com.soulfiremc.server.database.InstanceEntity;
import com.soulfiremc.server.settings.lib.InstanceSettingsSource;
import com.soulfiremc.server.settings.lib.SettingsObject;
import com.soulfiremc.server.settings.lib.SettingsSource;
import com.soulfiremc.server.settings.property.*;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.lenni0451.lambdaevents.EventHandler;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/// Plugin that automatically detects banned accounts and IP bans.
/// When a bot is disconnected with a message matching ban patterns, it will be marked as banned
/// and optionally removed from the account pool. For IP bans, the proxy is marked as banned
/// so it won't be used for other bots.
@Slf4j
@InternalPluginClass
public final class AutoBanReplacement extends InternalPlugin {
  // Track banned accounts per instance
  private static final MetadataKey<Set<UUID>> BANNED_ACCOUNTS =
    MetadataKey.of("auto-ban-replacement", "banned_accounts", Set.class);

  // Track banned proxies (IP bans) per instance
  private static final MetadataKey<Set<String>> BANNED_PROXIES =
    MetadataKey.of("auto-ban-replacement", "banned_proxies", Set.class);

  public AutoBanReplacement() {
    super(new PluginInfo(
      "auto-ban-replacement",
      "1.0.0",
      "Automatically detects banned accounts and IP bans",
      "At0Mic_X",
      "AGPL-3.0",
      "https://soulfiremc.com"));
  }

  @EventHandler
  public void onSettingsRegistryInit(InstanceSettingsRegistryInitEvent event) {
    event.settingsPageRegistry().addPluginPage(
      AutoBanReplacementSettings.class,
      "auto-ban-replacement",
      "Auto Ban Replacement",
      this,
      "user-x",
      AutoBanReplacementSettings.ENABLED);
  }

  @EventHandler
  public void onBotDisconnected(BotDisconnectedEvent event) {
    var bot = event.connection();
    var instanceManager = bot.instanceManager();
    var settingsSource = bot.settingsSource().instanceSettings();

    if (!settingsSource.get(AutoBanReplacementSettings.ENABLED) || bot.isStatusPing()) {
      return;
    }

    var message = PlainTextComponentSerializer.plainText().serialize(event.message());
    var banPatterns = settingsSource.get(AutoBanReplacementSettings.BAN_PATTERNS);
    var ipBanPatterns = settingsSource.get(AutoBanReplacementSettings.IP_BAN_PATTERNS);

    // Check if this is an IP ban first
    var isIpBanned = ipBanPatterns.stream()
      .anyMatch(pattern -> matchesPattern(pattern, message));

    if (isIpBanned) {
      handleIpBan(bot, instanceManager, message);
      return; // Don't also process as account ban
    }

    // Check if disconnect message matches any account ban pattern
    var isBanned = banPatterns.stream()
      .anyMatch(pattern -> matchesPattern(pattern, message));

    if (!isBanned) {
      return;
    }

    handleAccountBan(bot, instanceManager, settingsSource, message);
  }

  private boolean matchesPattern(String pattern, String message) {
    try {
      return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(message).find();
    } catch (Exception e) {
      // If pattern is invalid, fall back to simple contains check
      return message.toLowerCase().contains(pattern.toLowerCase());
    }
  }

  private void handleIpBan(
    com.soulfiremc.server.bot.BotConnection bot,
    InstanceManager instanceManager,
    String message) {

    var proxy = bot.proxy();
    if (proxy == null) {
      log.warn("Bot {} got IP banned but is not using a proxy! Message: {}", bot.accountName(), message);
      return;
    }

    var proxyAddress = proxy.address().toString();
    var bannedProxies = instanceManager.metadata()
      .getOrSet(BANNED_PROXIES, ConcurrentHashMap::newKeySet);

    if (bannedProxies.add(proxyAddress)) {
      log.error("🚫 PROXY IP BANNED: {} - Message: {}", proxyAddress, message);
      log.warn("Proxy {} has been marked as banned. All bots using this proxy should be avoided.", proxyAddress);

      // Count how many bots are using this banned proxy
      var affectedBots = instanceManager.getConnectedBots().stream()
        .filter(b -> b.proxy() != null && b.proxy().address().toString().equals(proxyAddress))
        .count();

      if (affectedBots > 1) {
        log.warn("⚠️ {} other bot(s) are using the banned proxy {}. They may also be affected.",
          affectedBots - 1, proxyAddress);
      }
    }
  }

  private void handleAccountBan(
    com.soulfiremc.server.bot.BotConnection bot,
    InstanceManager instanceManager,
    InstanceSettingsSource settingsSource,
    String message) {

    log.warn("Account {} appears to be banned: {}", bot.accountName(), message);

    // Mark account as banned
    var bannedAccounts = instanceManager.metadata()
      .getOrSet(BANNED_ACCOUNTS, ConcurrentHashMap::newKeySet);
    bannedAccounts.add(bot.accountProfileId());

    // Remove banned account from settings if configured
    if (settingsSource.get(AutoBanReplacementSettings.REMOVE_BANNED)) {
      removeBannedAccount(instanceManager, bot.accountProfileId());
    }

    // Log available replacement accounts
    if (settingsSource.get(AutoBanReplacementSettings.AUTO_REPLACE)) {
      var bannedAccountsFinal = bannedAccounts;
      var activeAccountIds = instanceManager.getConnectedBots().stream()
        .map(b -> b.accountProfileId())
        .toList();

      var unusedAccounts = instanceManager.settingsSource().accounts().values().stream()
        .filter(acc -> !activeAccountIds.contains(acc.profileId()))
        .filter(acc -> !bannedAccountsFinal.contains(acc.profileId()))
        .count();

      if (unusedAccounts == 0) {
        log.warn("No replacement accounts available. Add more accounts and restart session.");
      } else {
        log.info("{} unused accounts available. Restart session to use fresh accounts.", unusedAccounts);
      }
    }
  }

  private void removeBannedAccount(InstanceManager instanceManager, UUID accountId) {
    instanceManager.sessionFactory().inTransaction(session -> {
      var instanceEntity = session.find(InstanceEntity.class, instanceManager.id());
      if (instanceEntity == null) {
        return;
      }

      var currentSettings = instanceEntity.settings();
      var newAccounts = currentSettings.accounts().stream()
        .filter(acc -> !acc.profileId().equals(accountId))
        .toList();

      instanceEntity.settings(currentSettings.withAccounts(newAccounts));
      session.merge(instanceEntity);

      log.info("Removed banned account {} from instance settings", accountId);
    });
  }

  /// Get all banned accounts for an instance
  public static Set<UUID> getBannedAccounts(InstanceManager instanceManager) {
    return instanceManager.metadata().getOrSet(BANNED_ACCOUNTS, ConcurrentHashMap::newKeySet);
  }

  /// Get all banned proxies (IP bans) for an instance
  public static Set<String> getBannedProxies(InstanceManager instanceManager) {
    return instanceManager.metadata().getOrSet(BANNED_PROXIES, ConcurrentHashMap::newKeySet);
  }

  /// Check if a proxy is banned due to IP ban
  public static boolean isProxyBanned(InstanceManager instanceManager, String proxyAddress) {
    return getBannedProxies(instanceManager).contains(proxyAddress);
  }

    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    private static class AutoBanReplacementSettings implements SettingsObject {
        private static final String NAMESPACE = "auto-ban-replacement";

        public static final BooleanProperty<SettingsSource.Instance> ENABLED = ImmutableBooleanProperty.<SettingsSource.Instance>builder()
                .sourceType(SettingsSource.Instance.INSTANCE)
                .namespace(NAMESPACE)
                .key("enabled")
                .uiName("Enable Auto Ban Replacement")
                .description("Automatically detect and replace banned accounts")
                .defaultValue(false)
                .build();

        public static final BooleanProperty<SettingsSource.Instance> REMOVE_BANNED = ImmutableBooleanProperty.<SettingsSource.Instance>builder()
                .sourceType(SettingsSource.Instance.INSTANCE)
                .namespace(NAMESPACE)
                .key("remove-banned")
                .uiName("Remove Banned Accounts")
                .description("Remove banned accounts from the instance settings permanently")
                .defaultValue(true)
                .build();

        public static final BooleanProperty<SettingsSource.Instance> AUTO_REPLACE = ImmutableBooleanProperty.<SettingsSource.Instance>builder()
                .sourceType(SettingsSource.Instance.INSTANCE)
                .namespace(NAMESPACE)
                .key("auto-replace")
                .uiName("Auto Replace with Fresh Account")
                .description("Automatically connect a fresh account when one is banned")
                .defaultValue(true)
                .build();

        public static final StringListProperty<SettingsSource.Instance> BAN_PATTERNS = ImmutableStringListProperty.<SettingsSource.Instance>builder()
                .sourceType(SettingsSource.Instance.INSTANCE)
                .namespace(NAMESPACE)
                .key("ban-patterns")
                .uiName("Account Ban Detection Patterns")
                .description("Regex patterns to detect account ban messages (case insensitive)")
                .addAllDefaultValue(List.of(
                        "banned",
                        "permanently banned",
                        "temporarily banned",
                        "you have been banned",
                        "ban.*appeal",
                        "blacklisted",
                        "you are banned"))
                .build();

        public static final StringListProperty<SettingsSource.Instance> IP_BAN_PATTERNS = ImmutableStringListProperty.<SettingsSource.Instance>builder()
                .sourceType(SettingsSource.Instance.INSTANCE)
                .namespace(NAMESPACE)
                .key("ip-ban-patterns")
                .uiName("IP Ban Detection Patterns")
                .description("Regex patterns to detect IP/proxy bans (case insensitive). When matched, the proxy is marked as banned.")
                .addAllDefaultValue(List.of(
                        "ip.*banned",
                        "your ip",
                        "ip address.*banned",
                        "connection.*blocked",
                        "too many connections",
                        "rate.*limit",
                        "vpn.*detected",
                        "proxy.*detected"))
                .build();

        public static final MinMaxProperty<SettingsSource.Instance> REPLACEMENT_DELAY = ImmutableMinMaxProperty.<SettingsSource.Instance>builder()
                .sourceType(SettingsSource.Instance.INSTANCE)
                .namespace(NAMESPACE)
                .key("replacement-delay")
                .minValue(0)
                .maxValue(Integer.MAX_VALUE)
                .minEntry(ImmutableMinMaxPropertyEntry.builder()
                        .uiName("Min replacement delay (seconds)")
                        .description("Minimum delay before connecting replacement account")
                        .defaultValue(1)
                        .build())
                .maxEntry(ImmutableMinMaxPropertyEntry.builder()
                        .uiName("Max replacement delay (seconds)")
                        .description("Maximum delay before connecting replacement account")
                        .defaultValue(5)
                        .build())
                .build();
    }
}
