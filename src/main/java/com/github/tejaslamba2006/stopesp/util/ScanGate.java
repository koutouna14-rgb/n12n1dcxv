package com.github.tejaslamba2006.stopesp.util;

import com.github.tejaslamba2006.stopesp.manager.DeepShieldConfig;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

/** Decides whether a player's scan can be skipped this round (AFK, spectator, Bedrock throttling). */
public final class ScanGate {
   private ScanGate() {
   }

   public static boolean shouldSkip(DeepShieldConfig cfg, Player player, long counter) {
      if (cfg.isAfkSkipSpectators() && player.getGameMode() == GameMode.SPECTATOR) {
         return true;
      }

      int mult = 1;
      if (cfg.getBedrockScanMultiplier() > 1 && BedrockSupport.isBedrock(player)) {
         mult = cfg.getBedrockScanMultiplier();
      }

      if (cfg.isAfkEnabled() && ActivityTracker.idleSeconds(player) >= (long)cfg.getAfkIdleSeconds()) {
         mult = Math.max(mult, cfg.getAfkMultiplier());
      }

      return mult > 1 && counter % (long)mult != 0L;
   }
}
