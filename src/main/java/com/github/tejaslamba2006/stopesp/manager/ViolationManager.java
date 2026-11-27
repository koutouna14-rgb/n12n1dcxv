package com.github.tejaslamba2006.stopesp.manager;

import com.github.tejaslamba2006.stopesp.StopESP;
import com.github.tejaslamba2006.stopesp.util.Scheduler;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * Flags players who break blocks the server never showed them.
 *
 * Heuristic, not proof: mining straight through stone legitimately breaks hidden ore.
 * So a break only counts when it is NOT a continuation of a tunnel, i.e. the player
 * "jumped" to a hidden block far from whatever they broke last. This only alerts staff;
 * it never punishes anyone.
 */
public class ViolationManager {
   private static final long CHAIN_MEMORY_MS = 15000L;
   private final StopESP plugin;
   private final DeepShieldConfig cfg;
   private final Map<UUID, Record> records = new ConcurrentHashMap();
   private HttpClient http;

   private static final class Record {
      final ConcurrentLinkedDeque<Long> recent = new ConcurrentLinkedDeque();
      volatile int total;
      volatile long lastAlert;
      volatile UUID lastWorld;
      volatile int lastX;
      volatile int lastY;
      volatile int lastZ;
      volatile long lastBreakTime;
      volatile String lastDetail = "";
   }

   public ViolationManager(StopESP plugin, DeepShieldConfig cfg) {
      this.plugin = plugin;
      this.cfg = cfg;
   }

   /** Call from BlockBreakEvent BEFORE the block is removed from the index. */
   public void onBlockBreak(Player player, Block block, AntiXrayManager xray) {
      if (!this.cfg.isViolationsEnabled() || player.getGameMode() != GameMode.SURVIVAL || player.hasPermission("stopesp.bypass")) {
         return;
      }

      World world = block.getWorld();
      if (!this.cfg.isEnabledWorld(world)) {
         return;
      }

      Record r = (Record)this.records.computeIfAbsent(player.getUniqueId(), (k) -> new Record());
      long now = System.currentTimeMillis();
      int x = block.getX();
      int y = block.getY();
      int z = block.getZ();
      boolean continuesTunnel = false;
      if (r.lastWorld != null && r.lastWorld.equals(world.getUID()) && now - r.lastBreakTime <= CHAIN_MEMORY_MS) {
         double dx = (double)(x - r.lastX);
         double dy = (double)(y - r.lastY);
         double dz = (double)(z - r.lastZ);
         double max = this.cfg.getViolationChainDistance();
         continuesTunnel = dx * dx + dy * dy + dz * dz <= max * max;
      }

      r.lastWorld = world.getUID();
      r.lastX = x;
      r.lastY = y;
      r.lastZ = z;
      r.lastBreakTime = now;
      if (continuesTunnel || !xray.isHiddenProtected(player, x, y, z)) {
         return;
      }

      long windowMs = (long)this.cfg.getViolationWindowSeconds() * 1000L;
      r.recent.addLast(now);

      while(!r.recent.isEmpty() && now - (Long)r.recent.peekFirst() > windowMs) {
         r.recent.pollFirst();
      }

      ++r.total;
      int inWindow = r.recent.size();
      String detail = block.getType().name() + " at " + world.getName() + " " + x + " " + y + " " + z;
      r.lastDetail = detail;
      if (this.cfg.isViolationLogConsole()) {
         this.plugin.getLogger().info("[violation] " + player.getName() + " broke hidden " + detail + " (" + inWindow + " in window)");
      }

      long cooldownMs = (long)this.cfg.getViolationCooldownSeconds() * 1000L;
      if (inWindow >= this.cfg.getViolationThreshold() && now - r.lastAlert >= cooldownMs) {
         r.lastAlert = now;
         String msg = player.getName() + " broke " + inWindow + " hidden blocks in " + (this.cfg.getViolationWindowSeconds() / 60.0) + " min without a tunnel leading to them (latest: " + detail + "). Possible x-ray.";
         this.alertStaff(msg);
         this.sendWebhook(msg);
      }
   }

   private void alertStaff(String message) {
      String line = String.valueOf(ChatColor.AQUA) + "StopESP " + String.valueOf(ChatColor.DARK_GRAY) + "» " + String.valueOf(ChatColor.RED) + message;

      for(Player staff : Bukkit.getOnlinePlayers()) {
         if (staff.hasPermission("stopesp.alerts")) {
            Scheduler.runEntity(this.plugin, staff, () -> staff.sendMessage(line));
         }
      }

   }

   private void sendWebhook(String message) {
      String url = this.cfg.getDiscordWebhook();
      if (url.isEmpty()) {
         return;
      }

      if (!url.startsWith("https://discord.com/api/webhooks/") && !url.startsWith("https://discordapp.com/api/webhooks/")) {
         this.plugin.getLogger().warning("violations.discord-webhook is not a Discord webhook URL; ignoring it.");
         return;
      }

      Scheduler.runAsync(this.plugin, () -> {
         try {
            if (this.http == null) {
               this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5L)).build();
            }

            String json = "{\"username\":\"StopESP\",\"content\":\"" + escape("**StopESP alert** - " + message) + "\"}";
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10L)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build();
            HttpResponse<String> res = this.http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 300) {
               this.plugin.getLogger().warning("Discord webhook returned HTTP " + res.statusCode());
            }
         } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
         } catch (Exception e) {
            this.plugin.getLogger().warning("Discord webhook failed: " + e.getMessage());
         }

      });
   }

   private static String escape(String s) {
      StringBuilder sb = new StringBuilder();

      for(char c : s.toCharArray()) {
         switch (c) {
            case '"' -> sb.append("\\\"");
            case '\\' -> sb.append("\\\\");
            case '\n' -> sb.append("\\n");
            case '\r' -> {
            }
            default -> sb.append(c);
         }
      }

      return sb.toString();
   }

   /** Lines for /stopesp violations [player]. */
   public List<String> summary(String playerName) {
      List<String> out = new ArrayList();
      long windowMs = (long)this.cfg.getViolationWindowSeconds() * 1000L;
      long now = System.currentTimeMillis();

      for(Map.Entry<UUID, Record> e : this.records.entrySet()) {
         Player p = Bukkit.getPlayer((UUID)e.getKey());
         String name = p != null ? p.getName() : String.valueOf(Bukkit.getOfflinePlayer((UUID)e.getKey()).getName());
         if (playerName != null && !name.equalsIgnoreCase(playerName)) {
            continue;
         }

         Record r = (Record)e.getValue();
         int recent = 0;

         for(Long t : r.recent) {
            if (now - t <= windowMs) {
               ++recent;
            }
         }

         if (r.total > 0) {
            out.add(name + ": " + recent + " recent, " + r.total + " total" + (r.lastDetail.isEmpty() ? "" : ", last: " + r.lastDetail));
         }
      }

      return out;
   }

   public void cleanupPlayer(UUID uuid) {
      this.records.remove(uuid);
   }
}
