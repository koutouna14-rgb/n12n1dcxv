package com.github.tejaslamba2006.stopesp.util;

import com.github.tejaslamba2006.stopesp.manager.DeepShieldConfig;
import org.bukkit.Bukkit;

/** Stretches scan intervals when the server tick time is high. */
public final class PerformanceGovernor {
   private PerformanceGovernor() {
   }

   /** 1 = scan at the configured rate; higher = scan that many times less often. */
   public static int multiplier(DeepShieldConfig cfg) {
      if (!cfg.isTpsBackoffEnabled()) {
         return 1;
      }

      double mspt;
      try {
         mspt = Bukkit.getAverageTickTime();
      } catch (Throwable unsupported) {
         return 1; // Folia / non-Paper: no global tick time, so no backoff
      }

      double over = mspt - cfg.getMsptThreshold();
      if (over <= 0.0) {
         return 1;
      }

      return Math.min(cfg.getMaxMultiplier(), 1 + (int)Math.ceil(over / 10.0));
   }
}
