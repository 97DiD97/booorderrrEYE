package dev.eyesky.border;

import dev.eyesky.EyeSkyPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class EyeBorderPlugin extends JavaPlugin {
    private PluginCommand eyeCmd;
    private CommandExecutor origExec;
    private TabCompleter origTab;
    private EyeSkyPlugin sky;
    private BorderExpansion active;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Plugin p = getServer().getPluginManager().getPlugin("FloatingEye");
        if (!(p instanceof EyeSkyPlugin s)) {
            getLogger().severe("FloatingEye не найден - отключаюсь.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        sky = s;
        eyeCmd = getServer().getPluginCommand("eye");
        if (eyeCmd == null) {
            getLogger().severe("Команда /eye не найдена - отключаюсь.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        origExec = eyeCmd.getExecutor();
        origTab = eyeCmd.getTabCompleter();
        eyeCmd.setExecutor((sender, command, label, args) -> {
            if (args.length > 0 && isBorder(args[0])) {
                handle(sender, args);
                return true;
            }
            return origExec != null && origExec.onCommand(sender, command, label, args);
        });
        eyeCmd.setTabCompleter((sender, command, alias, args) -> {
            if (args.length > 0 && isBorder(args[0])) return complete(args);
            List<String> base = origTab == null ? null : origTab.onTabComplete(sender, command, alias, args);
            List<String> out = base == null ? new ArrayList<>() : new ArrayList<>(base);
            if (args.length == 1 && "border".startsWith(args[0].toLowerCase(Locale.ROOT)) && !out.contains("border")) {
                out.add("border");
            }
            return out;
        });
        getLogger().info("Команда /eye border подключена.");
    }

    @Override
    public void onDisable() {
        if (active != null) active.abort(false);
        active = null;
        if (eyeCmd != null) {
            eyeCmd.setExecutor(origExec);
            eyeCmd.setTabCompleter(origTab);
        }
    }

    void cleared(BorderExpansion e) {
        if (active == e) active = null;
    }

    private static boolean isBorder(String s) {
        return s.equalsIgnoreCase("border") || s.equalsIgnoreCase("граница");
    }

    private static void msg(CommandSender s, String text, NamedTextColor c) {
        s.sendMessage(Component.text(text, c));
    }

    private void handle(CommandSender sender, String[] args) {
        if (args.length < 2) {
            usage(sender);
            return;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        if (sub.equals("stop")) {
            if (active == null) { msg(sender, "Глаз сейчас ничего не расширяет.", NamedTextColor.GRAY); return; }
            active.abort(true);
            msg(sender, "Расширение остановлено.", NamedTextColor.YELLOW);
            return;
        }
        if (sub.equals("limit")) {
            if (args.length < 3) {
                int cur = getConfig().getInt("border.max-blocks", 5000);
                msg(sender, "Лимит: " + (cur <= 0 ? "нет" : cur + " блоков") + ". Изменить: /eye border limit <число> (0 = без лимита)", NamedTextColor.AQUA);
                return;
            }
            try {
                int v = Integer.parseInt(args[2]);
                if (v < 0) throw new NumberFormatException();
                getConfig().set("border.max-blocks", v);
                saveConfig();
                msg(sender, "Лимит расширения теперь: " + (v == 0 ? "без лимита" : v + " блоков"), NamedTextColor.GREEN);
            } catch (NumberFormatException e) {
                msg(sender, "Нужно целое число, например /eye border limit 10000", NamedTextColor.RED);
            }
            return;
        }
        if (sub.equals("reload")) {
            reloadConfig();
            msg(sender, "Настройки границы перезагружены.", NamedTextColor.GREEN);
            return;
        }
        int blocks;
        try {
            blocks = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            usage(sender);
            return;
        }
        if (blocks < 1) { msg(sender, "Блоков должно быть хотя бы 1.", NamedTextColor.RED); return; }
        int max = getConfig().getInt("border.max-blocks", 5000);
        if (max > 0 && blocks > max) {
            msg(sender, "Это больше лимита (" + max + "). Поменять лимит: /eye border limit <число>", NamedTextColor.RED);
            return;
        }
        int secs = getConfig().getInt("border.default-seconds", 14);
        if (args.length >= 3) {
            try { secs = Integer.parseInt(args[2]); } catch (NumberFormatException e) { usage(sender); return; }
        }
        secs = Math.max(3, Math.min(180, secs));
        if (active != null) { msg(sender, "Глаз уже расширяет границу. /eye border stop - остановить.", NamedTextColor.RED); return; }
        try {
            active = new BorderExpansion(this, sky, sender, blocks, secs);
            active.start();
        } catch (IllegalStateException e) {
            active = null;
            msg(sender, e.getMessage(), NamedTextColor.RED);
        }
    }

    private void usage(CommandSender s) {
        msg(s, "/eye border <блоков> [секунд] | stop | limit [число] | reload", NamedTextColor.YELLOW);
    }

    private List<String> complete(String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 2) {
            for (String s : new String[]{"100", "500", "1000", "2000", "stop", "limit", "reload"}) {
                if (s.startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(s);
            }
        } else if (args.length == 3 && !args[1].equalsIgnoreCase("limit") && !args[1].equalsIgnoreCase("stop")) {
            for (String s : new String[]{"8", "14", "20", "30", "60"}) if (s.startsWith(args[2])) out.add(s);
        } else if (args.length == 3 && args[1].equalsIgnoreCase("limit")) {
            for (String s : new String[]{"0", "1000", "5000", "20000"}) if (s.startsWith(args[2])) out.add(s);
        }
        return out;
    }
}
