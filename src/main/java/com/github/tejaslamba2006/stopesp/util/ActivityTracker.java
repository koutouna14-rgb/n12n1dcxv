package com.github.tejaslamba2006.stopesp.util;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;

/** Tracks when each player last moved or looked around, for AFK scan backoff. */
public final class ActivityTracker {
   private static final Map<UUID, Activity> DATA = new ConcurrentHashMap();

   private static final class Activity {
      double x;
      double y;
      double z;
      float yaw;
      float pitch;
      volatile long lastActive = System.currentTimeMillis();
   }

   private ActivityTracker() {
   }

   /** Seconds since the player last moved or rotated. Safe to call from several managers per tick. */
   public static long idleSeconds(Player player) {
      Activity a = (Activity)DATA.computeIfAbsent(player.getUniqueId(), (k) -> new Activity());
      double x = player.getX();
      double y = player.getY();
      double z = player.getZ();
      float yaw = player.getYaw();
      float pitch = player.getPitch();
      long now = System.currentTimeMillis();
      synchronized(a) {
         double dx = x - a.x;
         double dy = y - a.y;
         double dz = z - a.z;
         if (dx * dx + dy * dy + dz * dz > 0.0025 || Math.abs(yaw - a.yaw) > 1.0F || Math.abs(pitch - a.pitch) > 1.0F) {
            a.lastActive = now;
            a.x = x;
            a.y = y;
            a.z = z;
            a.yaw = yaw;
            a.pitch = pitch;
         }
      }

      return (now - a.lastActive) / 1000L;
   }

   public static void remove(UUID id) {
      DATA.remove(id);
   }
}
