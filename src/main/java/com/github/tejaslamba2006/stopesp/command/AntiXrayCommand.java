package com.github.tejaslamba2006.stopesp.command;

import com.github.tejaslamba2006.stopesp.StopESP;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class AntiXrayCommand implements CommandExecutor {
   private final StopESP plugin;

   public AntiXrayCommand(StopESP plugin) {
      this.plugin = plugin;
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (args.length > 0 && args[0].equalsIgnoreCase("friend")) {
         return this.handleFriend(sender, args);
      } else if (!sender.hasPermission("stopesp.admin")) {
         sender.sendMessage(String.valueOf(ChatColor.RED) + "No permission!");
         return true;
      } else if (args.length == 0) {
         this.sendUsage(sender);
         return true;
      } else {
         switch (args[0].toLowerCase()) {
            case "reload":
               this.plugin.getAntiXrayModule().getManager().getDsConfig().reload();
               sender.sendMessage(String.valueOf(ChatColor.GREEN) + "StopESP configuration reloaded!");
               return true;
            case "on":
               this.plugin.getAntiXrayModule().getManager().startScan();
               String var9 = String.valueOf(ChatColor.GREEN);
               sender.sendMessage(var9 + "StopESP has been " + String.valueOf(ChatColor.BOLD) + "ENABLED" + String.valueOf(ChatColor.RESET) + String.valueOf(ChatColor.GREEN) + "!");
               return true;
            case "off":
               this.plugin.getAntiXrayModule().getManager().stopTask();
               String var8 = String.valueOf(ChatColor.RED);
               sender.sendMessage(var8 + "StopESP has been " + String.valueOf(ChatColor.BOLD) + "DISABLED" + String.valueOf(ChatColor.RESET) + String.valueOf(ChatColor.RED) + " and all blocks revealed!");
               return true;
            case "violations":
               java.util.List<String> lines = this.plugin.getAntiXrayModule().getViolations().summary(args.length > 1 ? args[1] : null);
               if (lines.isEmpty()) {
                  sender.sendMessage(String.valueOf(ChatColor.GREEN) + "No violations recorded.");
               } else {
                  sender.sendMessage(String.valueOf(ChatColor.AQUA) + "--- StopESP Violations ---");
                  for(String l : lines) {
                     sender.sendMessage(String.valueOf(ChatColor.GRAY) + "  " + l);
                  }
               }

               return true;
            case "debug":
               if (args.length < 2) {
                  sender.sendMessage(String.valueOf(ChatColor.RED) + "Usage: /stopesp debug <player>");
                  return true;
               } else {
                  Player target = Bukkit.getPlayer(args[1]);
                  if (target == null) {
                     String var10001 = String.valueOf(ChatColor.RED);
                     sender.sendMessage(var10001 + "Player \"" + args[1] + "\" not found or offline.");
                     return true;
                  }

                  this.sendDebug(sender, target);
                  return true;
               }
            default:
               this.sendUsage(sender);
               return true;
         }
      }
   }

   private boolean handleFriend(CommandSender sender, String[] args) {
      if (!(sender instanceof Player me)) {
         sender.sendMessage(String.valueOf(ChatColor.RED) + "Only players can use friends.");
         return true;
      } else if (!sender.hasPermission("stopesp.friend")) {
         sender.sendMessage(String.valueOf(ChatColor.RED) + "No permission!");
         return true;
      } else {
         com.github.tejaslamba2006.stopesp.manager.FriendManager fm = this.plugin.getFriendManager();
         String sub = args.length > 1 ? args[1].toLowerCase() : "list";
         if (sub.equals("list")) {
            java.util.Set<java.util.UUID> mine = fm.list(me.getUniqueId());
            if (mine.isEmpty()) {
               me.sendMessage(String.valueOf(ChatColor.GRAY) + "You have no friends added. Use /stopesp friend add <player>.");
            } else {
               me.sendMessage(String.valueOf(ChatColor.AQUA) + "--- StopESP Friends ---");

               for(java.util.UUID id : mine) {
                  String name = String.valueOf(Bukkit.getOfflinePlayer(id).getName());
                  boolean mutual = fm.areMutualFriends(me.getUniqueId(), id);
                  me.sendMessage(String.valueOf(ChatColor.GRAY) + "  " + name + (mutual ? String.valueOf(ChatColor.GREEN) + " (mutual)" : String.valueOf(ChatColor.YELLOW) + " (waiting for them to add you)"));
               }
            }

            return true;
         } else if ((sub.equals("add") || sub.equals("remove")) && args.length >= 3) {
            org.bukkit.OfflinePlayer other = Bukkit.getOfflinePlayer(args[2]);
            if (!other.isOnline() && !other.hasPlayedBefore()) {
               me.sendMessage(String.valueOf(ChatColor.RED) + "Unknown player \"" + args[2] + "\".");
               return true;
            } else if (other.getUniqueId().equals(me.getUniqueId())) {
               me.sendMessage(String.valueOf(ChatColor.RED) + "You can't add yourself.");
               return true;
            } else {
               if (sub.equals("add")) {
                  fm.add(me.getUniqueId(), other.getUniqueId());
                  me.sendMessage(String.valueOf(ChatColor.GREEN) + "Added " + other.getName() + ". You will see each other through walls once they add you too.");
                  Player online = other.getPlayer();
                  if (online != null) {
                     online.sendMessage(String.valueOf(ChatColor.AQUA) + me.getName() + " added you as a StopESP friend. Run /stopesp friend add " + me.getName() + " to see each other.");
                  }
               } else {
                  fm.remove(me.getUniqueId(), other.getUniqueId());
                  me.sendMessage(String.valueOf(ChatColor.YELLOW) + "Removed " + other.getName() + ".");
               }

               return true;
            }
         } else {
            me.sendMessage(String.valueOf(ChatColor.RED) + "Usage: /stopesp friend <add|remove|list> [player]");
            return true;
         }
      }
   }

   private void sendDebug(CommandSender sender, Player target) {
      String[] lines = this.plugin.getAntiXrayModule().getManager().getDebugInfo(target);
      String var10001 = String.valueOf(ChatColor.AQUA);
      sender.sendMessage(var10001 + "--- StopESP Debug: " + String.valueOf(ChatColor.WHITE) + target.getName() + String.valueOf(ChatColor.AQUA) + " ---");

      for(String line : lines) {
         var10001 = String.valueOf(ChatColor.GRAY);
         sender.sendMessage(var10001 + "  " + line);
      }

      sender.sendMessage(String.valueOf(ChatColor.AQUA) + "-------------------------------------------");
   }

   private void sendUsage(CommandSender sender) {
      sender.sendMessage(String.valueOf(ChatColor.YELLOW) + "StopESP Commands:");
      String var10001 = String.valueOf(ChatColor.GRAY);
      sender.sendMessage(var10001 + "  /stopesp reload         " + String.valueOf(ChatColor.WHITE) + "- Reload config");
      var10001 = String.valueOf(ChatColor.GRAY);
      sender.sendMessage(var10001 + "  /stopesp on             " + String.valueOf(ChatColor.WHITE) + "- Enable the plugin");
      var10001 = String.valueOf(ChatColor.GRAY);
      sender.sendMessage(var10001 + "  /stopesp off            " + String.valueOf(ChatColor.WHITE) + "- Disable and reveal all");
      var10001 = String.valueOf(ChatColor.GRAY);
      sender.sendMessage(var10001 + "  /stopesp debug <player> " + String.valueOf(ChatColor.WHITE) + "- Show debug info for a player");
      sender.sendMessage(String.valueOf(ChatColor.GRAY) + "  /stopesp violations [p] " + String.valueOf(ChatColor.WHITE) + "- Show x-ray violation counts");
   }
}
