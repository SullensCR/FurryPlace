package com.furryplace.event.domain;

import org.bukkit.command.CommandSender;

public enum Role {
    ADMIN,
    JUDGE,
    PLAYER;

    public static Role resolve(CommandSender sender) {
        if (sender.hasPermission("furryplace.admin")) {
            return ADMIN;
        }
        if (sender.hasPermission("furryplace.judge")) {
            return JUDGE;
        }
        return PLAYER;
    }
}

