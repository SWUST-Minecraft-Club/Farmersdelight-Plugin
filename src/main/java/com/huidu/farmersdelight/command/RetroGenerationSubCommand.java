package com.huidu.farmersdelight.command;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/** Diagnostics are available in the ordinary release; preview never changes blocks or completion marks. */
final class RetroGenerationSubCommand extends SubCommand {
    private final FarmersDelightPlugin plugin;

    RetroGenerationSubCommand(FarmersDelightPlugin plugin) {
        super("retrogen", List.of(), "farmersdelight.admin", "command.help_retrogen");
        this.plugin = plugin;
    }

    @Override
    void execute(CommandSender sender, String label, String[] args) {
        var service = plugin.retroGeneration();
        if (service == null) {
            sender.sendMessage(I18n.getComponent("retrogen.preview_unavailable"));
            return;
        }
        if (args.length == 1 || args.length == 2 && args[1].equalsIgnoreCase("status")) {
            sender.sendMessage(I18n.getComponent("retrogen.status", service.status()));
            sender.sendMessage(I18n.getComponent("retrogen.status_details", service.diagnostics()));
        } else if (args.length == 2 && args[1].equalsIgnoreCase("preview") && sender instanceof Player player) {
            plugin.scheduler().runForEntity(player, () -> service.preview(player));
        } else if (args.length == 2 && args[1].equalsIgnoreCase("locate") && sender instanceof Player player) {
            plugin.scheduler().runForEntity(player, () -> service.locate(player));
        } else {
            sender.sendMessage(I18n.getComponent("retrogen.usage_commands"));
        }
    }

    @Override
    List<String> tabComplete(CommandSender sender, String[] args) {
        return args.length == 2 ? CommandSupport.prefixFilter(args[1], List.of("status", "preview", "locate")) : List.of();
    }
}
