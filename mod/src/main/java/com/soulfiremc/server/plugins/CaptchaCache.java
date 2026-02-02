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
import com.soulfiremc.server.api.metadata.MetadataKey;
import com.soulfiremc.server.settings.lib.SettingsObject;
import com.soulfiremc.server.settings.lib.SettingsSource;
import com.soulfiremc.server.settings.property.BooleanProperty;
import com.soulfiremc.server.settings.property.ImmutableBooleanProperty;
import com.soulfiremc.server.settings.property.ImmutableIntProperty;
import com.soulfiremc.server.settings.property.IntProperty;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.lenni0451.lambdaevents.EventHandler;

import java.awt.image.BufferedImage;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/// Plugin that caches captcha answers to avoid repeated API calls.
/// Uses perceptual hashing for efficient lookup of map captchas.
/// Cache is per-server to avoid cross-contamination between different captcha pools.
@Slf4j
@InternalPluginClass
public final class CaptchaCache extends InternalPlugin {
  /// Per-server cache: serverAddress -> (imageHash -> entry)
  private static final Map<String, Map<Long, CacheEntry>> SERVER_CACHES = new ConcurrentHashMap<>();

  /// Cache statistics per server
  private static final Map<String, AtomicLong> CACHE_HITS = new ConcurrentHashMap<>();
  private static final Map<String, AtomicLong> CACHE_MISSES = new ConcurrentHashMap<>();

  /// Metadata key for per-instance cache stats
  private static final MetadataKey<CacheStats> CACHE_STATS_KEY =
      MetadataKey.of("captcha_cache", "stats", CacheStats.class);

  public CaptchaCache() {
    super(new PluginInfo(
        "captcha-cache",
        "1.0.0",
        "Caches map captcha answers to avoid repeated API calls (per-server)",
        "At0Mic_X",
        "AGPL-3.0",
        "https://soulfiremc.com"));
  }

  @EventHandler
  public void onSettingsRegistryInit(InstanceSettingsRegistryInitEvent event) {
    event.settingsPageRegistry().addPluginPage(
        CaptchaCacheSettings.class,
        "captcha-cache",
        "Captcha Cache",
        this,
        "database",
        CaptchaCacheSettings.ENABLED);
  }

  /// Calculate perceptual hash (pHash) of an image.
  /// Produces a 64-bit fingerprint that's robust to minor image variations.
  public static long calculatePHash(BufferedImage image) {
    // Resize to 8x8 for DCT-like comparison
    var resized = new BufferedImage(8, 8, BufferedImage.TYPE_BYTE_GRAY);
    var g = resized.createGraphics();
    g.drawImage(image, 0, 0, 8, 8, null);
    g.dispose();

    // Calculate average pixel value
    long sum = 0;
    var pixels = new int[64];
    for (int y = 0; y < 8; y++) {
      for (int x = 0; x < 8; x++) {
        var pixel = resized.getRGB(x, y) & 0xFF;
        pixels[y * 8 + x] = pixel;
        sum += pixel;
      }
    }
    var avg = sum / 64;

    // Generate hash: 1 if pixel > avg, 0 otherwise
    long hash = 0;
    for (int i = 0; i < 64; i++) {
      if (pixels[i] > avg) {
        hash |= (1L << i);
      }
    }

    return hash;
  }

  /// Alternative: Calculate MD5 hash for exact matching
  public static String calculateMD5(byte[] imageBytes) {
    try {
      var md = MessageDigest.getInstance("MD5");
      var digest = md.digest(imageBytes);
      var sb = new StringBuilder();
      for (byte b : digest) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException("MD5 not available", e);
    }
  }

  /// Look up a cached answer for the given image on a specific server
  public static Optional<String> lookup(String serverAddress, BufferedImage image) {
    var cache = SERVER_CACHES.get(serverAddress);
    if (cache == null) {
      CACHE_MISSES.computeIfAbsent(serverAddress, k -> new AtomicLong()).incrementAndGet();
      return Optional.empty();
    }

    var hash = calculatePHash(image);
    var entry = cache.get(hash);
    if (entry != null) {
      CACHE_HITS.computeIfAbsent(serverAddress, k -> new AtomicLong()).incrementAndGet();
      log.debug("Captcha cache HIT for {}: hash={}, answer={}", serverAddress, hash, entry.answer);
      return Optional.of(entry.answer);
    }
    CACHE_MISSES.computeIfAbsent(serverAddress, k -> new AtomicLong()).incrementAndGet();
    log.debug("Captcha cache MISS for {}: hash={}", serverAddress, hash);
    return Optional.empty();
  }

  /// Store a captcha answer in the cache for a specific server
  public static void store(String serverAddress, BufferedImage image, String answer) {
    var cache = SERVER_CACHES.computeIfAbsent(serverAddress, k -> new ConcurrentHashMap<>());
    
    // Enforce max cache size per server to prevent unbounded memory growth
    if (cache.size() >= 5000) {
      log.warn("Captcha cache for {} at max size (5000), skipping store", serverAddress);
      return;
    }
    
    var hash = calculatePHash(image);
    cache.put(hash, new CacheEntry(answer, System.currentTimeMillis()));
    log.debug("Captcha cached for {}: hash={}, answer={}, total={}", serverAddress, hash, answer, cache.size());
  }

  /// Clear cache for a specific server
  public static void clearForServer(String serverAddress) {
    var cache = SERVER_CACHES.remove(serverAddress);
    CACHE_HITS.remove(serverAddress);
    CACHE_MISSES.remove(serverAddress);
    if (cache != null) {
      log.info("Captcha cache cleared for {}: {} entries removed", serverAddress, cache.size());
    }
  }

  /// Clear all cached captchas across all servers
  public static void clearAll() {
    var totalSize = SERVER_CACHES.values().stream().mapToInt(Map::size).sum();
    var serverCount = SERVER_CACHES.size();
    SERVER_CACHES.clear();
    CACHE_HITS.clear();
    CACHE_MISSES.clear();
    log.info("Captcha cache cleared: {} entries across {} servers removed", totalSize, serverCount);
  }

  /// Get list of all cached server addresses
  public static Set<String> getCachedServers() {
    return SERVER_CACHES.keySet();
  }

  /// Get cache statistics for a specific server
  public static CacheStats getStats(String serverAddress) {
    var cache = SERVER_CACHES.get(serverAddress);
    var hits = CACHE_HITS.getOrDefault(serverAddress, new AtomicLong()).get();
    var misses = CACHE_MISSES.getOrDefault(serverAddress, new AtomicLong()).get();
    return new CacheStats(
        cache != null ? cache.size() : 0,
        hits,
        misses
    );
  }

  /// Get total cache statistics across all servers
  public static CacheStats getTotalStats() {
    var totalSize = SERVER_CACHES.values().stream().mapToInt(Map::size).sum();
    var totalHits = CACHE_HITS.values().stream().mapToLong(AtomicLong::get).sum();
    var totalMisses = CACHE_MISSES.values().stream().mapToLong(AtomicLong::get).sum();
    return new CacheStats(totalSize, totalHits, totalMisses);
  }

  /// Cache entry with answer and timestamp
  private record CacheEntry(String answer, long timestamp) {}

  /// Cache statistics record
  public record CacheStats(int size, long hits, long misses) {
    public double hitRate() {
      var total = hits + misses;
      return total == 0 ? 0 : (double) hits / total * 100;
    }
  }

  @NoArgsConstructor(access = AccessLevel.PRIVATE)
  public static class CaptchaCacheSettings implements SettingsObject {
    private static final String NAMESPACE = "captcha-cache";

    public static final BooleanProperty<SettingsSource.Instance> ENABLED =
        ImmutableBooleanProperty.<SettingsSource.Instance>builder()
            .sourceType(SettingsSource.Instance.INSTANCE)
            .namespace(NAMESPACE)
            .key("enabled")
            .uiName("Enable Captcha Cache")
            .description("Cache map captcha answers to avoid repeated API calls. Cache is per-server.")
            .defaultValue(false)
            .build();

    public static final IntProperty<SettingsSource.Instance> MAX_CACHE_SIZE =
        ImmutableIntProperty.<SettingsSource.Instance>builder()
            .sourceType(SettingsSource.Instance.INSTANCE)
            .namespace(NAMESPACE)
            .key("max-cache-size")
            .uiName("Max Cache Size Per Server")
            .description("Maximum number of captchas to cache per server. Set to 0 for unlimited.")
            .defaultValue(5000)
            .minValue(0)
            .maxValue(100000)
            .build();

    public static final BooleanProperty<SettingsSource.Instance> PERSIST_CACHE =
        ImmutableBooleanProperty.<SettingsSource.Instance>builder()
            .sourceType(SettingsSource.Instance.INSTANCE)
            .namespace(NAMESPACE)
            .key("persist-cache")
            .uiName("Persist Cache to Disk")
            .description("Save cache to disk so it survives server restarts")
            .defaultValue(false)
            .build();
  }
}
