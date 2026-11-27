package com.github.tejaslamba2006.stopesp.listener;

import com.github.tejaslamba2006.stopesp.StopESP;
import com.github.tejaslamba2006.stopesp.manager.AntiEspManager;
import com.github.tejaslamba2006.stopesp.util.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

public class AntiEspListener implements Listener {
   private final StopESP plugin;
   private final AntiEspManager manager;

   public AntiEspListener(StopESP plugin, AntiEspManager manager) {
      this.plugin = plugin;
      this.manager = manager;
   }

   @EventHandler(
      priority = EventPriority.MONITOR
   )
   public void onQuit(PlayerQuitEvent event) {
      this.manager.forgetTarget(event.getPlayer().getUniqueId());
      Scheduler.runEntity(this.plugin, event.getPlayer(), () -> this.manager.cleanupPlayer(event.getPlayer()));
   }

   // Hide-first: the entity tracker pairs players at the end of the tick, usually before our async scan has
   // run, so without this a nametag cheat sees everyone nearby for a few ticks after a join or teleport.
   // On Paper these run inside the event (before the tracker), so the first thing the client ever
   // receives is "nothing"; line of sight then reveals whoever is really visible within a tick or two.

   @EventHandler(priority = EventPriority.MONITOR)
   public void onJoin(PlayerJoinEvent event) {
      this.closeBoth(event.getPlayer(), event.getPlayer().getLocation());
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onWorldChange(PlayerChangedWorldEvent event) {
      this.closeBoth(event.getPlayer(), event.getPlayer().getLocation());
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onRespawn(PlayerRespawnEvent event) {
      this.closeBoth(event.getPlayer(), event.getRespawnLocation());
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onTeleport(PlayerTeleportEvent event) {
      Location to = event.getTo();
      Location from = event.getFrom();
      // Small hops (pearls, corrections) are covered by the normal scan; only long jumps need hide-first.
      if (to == null || to.getWorld() == null || (to.getWorld().equals(from.getWorld()) && from.distanceSquared(to) < 16.0 * 16.0)) {
         return;
      }
      this.closeBoth(event.getPlayer(), to);
   }

   private void closeBoth(Player player, Location around) {
      if (around == null || around.getWorld() == null) {
         return;
      }
      Location where = around.clone();
      Runnable task = () -> {
         if (player.isOnline()) {
            this.manager.failCloseViewer(player, where);
            this.manager.failCloseTarget(player, where);
         }
      };
      if (!Scheduler.isFolia() && Bukkit.isPrimaryThread()) {
         task.run(); // before the tracker update at the end of this tick
      } else {
         Scheduler.runEntity(this.plugin, player, task);
      }
   }
}
