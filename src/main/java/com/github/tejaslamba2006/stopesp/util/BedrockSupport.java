package com.github.tejaslamba2006.stopesp.util;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;

/** Detects Bedrock (Geyser/Floodgate) players. Uses Floodgate's API when present, else its UUID format. */
public final class BedrockSupport {
   private static Object floodgate;
   private static Method isFloodgatePlayer;
   private static boolean resolved;
   private static final Map<UUID, Boolean> CACHE = new ConcurrentHashMap();

   private BedrockSupport() {
   }

   private static synchronized void resolve() {
      if (resolved) {
         return;
      }

      resolved = true;

      try {
         Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
         floodgate = api.getMethod("getInstance").invoke(null);
         isFloodgatePlayer = api.getMethod("isFloodgatePlayer", UUID.class);
      } catch (ReflectiveOperationException | LinkageError e) {
         floodgate = null;
         isFloodgatePlayer = null;
      }
   }

   public static void forget(UUID id) {
      CACHE.remove(id);
   }

   public static boolean isBedrock(Player player) {
      if (player == null) {
         return false;
      }

      return (Boolean)CACHE.computeIfAbsent(player.getUniqueId(), (id) -> detect(id));
   }

   private static boolean detect(UUID id) {
      if (!resolved) {
         resolve();
      }

      if (floodgate != null && isFloodgatePlayer != null) {
         try {
            return (Boolean)isFloodgatePlayer.invoke(floodgate, id);
         } catch (ReflectiveOperationException | RuntimeException e) {
            // fall through to the UUID check
         }
      }

      // Floodgate-generated UUIDs have zero in their most significant 64 bits.
      return id.getMostSignificantBits() == 0L;
   }
}
