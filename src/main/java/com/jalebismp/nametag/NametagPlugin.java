package com.jalebismp.nametag;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.NotNull;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class NametagPlugin extends JavaPlugin implements Listener {

    private static final String TEAM_NAME = "jalebi_nt";

    // Keys of the checkboxes inside the dialog
    private static final String KEY_NAME = "show_name";
    private static final String KEY_MONEY = "show_money";
    private static final String KEY_HEARTS = "show_hearts";
    private static final String KEY_KILLS = "show_kills";
    private static final String KEY_DEATHS = "show_deaths";

    // ---- Colours / icons (change these if you want a different look) ----
    private static final TextColor HEART_COLOR = NamedTextColor.DARK_RED;          // dark red heart
    private static final TextColor MONEY_COLOR = NamedTextColor.DARK_GREEN;        // darker green $
    private static final TextColor KILLS_COLOR = NamedTextColor.RED;               // bright red sword
    private static final TextColor DEATHS_COLOR = TextColor.color(0xFF8000);       // bright orange skull
    private static final String HEART_ICON = "\u2764"; // heart
    private static final String MONEY_ICON = "$";
    private static final String KILLS_ICON = "\u2694"; // crossed swords
    private static final String DEATHS_ICON = "\u2620"; // skull and crossbones

    private final Map<UUID, Settings> settings = new HashMap<>();
    private final Map<UUID, TextDisplay> displays = new HashMap<>();
    private Economy economy;
    private File dataFile;
    private Team hiddenNameTeam;

    /** Per-player toggles. Everything is ON by default. */
    private static class Settings {
        boolean name = true, money = true, hearts = true, kills = true, deaths = true;
    }

    // ------------------------------------------------------------------ small caps helper

    private static final String SMALL_CAPS = "\u1D00\u0299\u1D04\u1D05\u1D07\uA730\u0262\u029C\u026A\u1D0A\u1D0B\u029F\u1D0D\u0274\u1D0F\u1D18\u01EB\u0280\u0455\u1D1B\u1D1C\u1D20\u1D21\u0445\u028F\u1D22";
    // = ᴀ ʙ ᴄ ᴅ ᴇ ꜰ ɢ ʜ ɪ ᴊ ᴋ ʟ ᴍ ɴ ᴏ ᴘ ǫ ʀ ѕ ᴛ ᴜ ᴠ ᴡ х ʏ ᴢ

    /** Converts normal letters to the small-caps style, e.g. "Kills" -> "ᴋɪʟʟѕ". */
    private static String sc(String text) {
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            char lower = Character.toLowerCase(c);
            if (lower >= 'a' && lower <= 'z') {
                sb.append(SMALL_CAPS.charAt(lower - 'a'));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        dataFile = new File(getDataFolder(), "data.yml");
        loadData();
        setupEconomy();
        setupTeam();

        Bukkit.getPluginManager().registerEvents(this, this);

        for (Player p : Bukkit.getOnlinePlayers()) {
            hiddenNameTeam.addEntry(p.getName());
        }

        // Refresh hearts / money / kills / deaths regularly (every 10 ticks = 0.5s)
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                update(p);
            }
        }, 20L, 10L);

        getLogger().info("JalebiNametag enabled" + (economy == null ? " (Vault economy not found - money line disabled)" : "") + ".");
    }

    @Override
    public void onDisable() {
        for (TextDisplay d : displays.values()) {
            if (d.isValid()) d.remove();
        }
        displays.clear();
        if (hiddenNameTeam != null) {
            try {
                hiddenNameTeam.unregister();
            } catch (IllegalStateException ignored) {
            }
        }
        saveData();
    }

    private void setupEconomy() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) return;
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp != null) economy = rsp.getProvider();
    }

    private void setupTeam() {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = sb.getTeam(TEAM_NAME);
        if (team == null) team = sb.registerNewTeam(TEAM_NAME);
        // Hide the vanilla nametag - we draw the username ourselves so it can be toggled.
        team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        hiddenNameTeam = team;
    }

    // ------------------------------------------------------------------ persistence

    private void loadData() {
        if (!dataFile.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection sec = yml.getConfigurationSection("players");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                Settings s = new Settings();
                s.name = sec.getBoolean(key + ".name", true);
                s.money = sec.getBoolean(key + ".money", true);
                s.hearts = sec.getBoolean(key + ".hearts", true);
                s.kills = sec.getBoolean(key + ".kills", true);
                s.deaths = sec.getBoolean(key + ".deaths", true);
                settings.put(id, s);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void saveData() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<UUID, Settings> e : settings.entrySet()) {
            String base = "players." + e.getKey();
            yml.set(base + ".name", e.getValue().name);
            yml.set(base + ".money", e.getValue().money);
            yml.set(base + ".hearts", e.getValue().hearts);
            yml.set(base + ".kills", e.getValue().kills);
            yml.set(base + ".deaths", e.getValue().deaths);
        }
        try {
            getDataFolder().mkdirs();
            yml.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Could not save data.yml: " + ex.getMessage());
        }
    }

    private Settings settings(Player p) {
        return settings.computeIfAbsent(p.getUniqueId(), k -> new Settings());
    }

    // ------------------------------------------------------------------ overhead display

    private Component line(String icon, TextColor iconColor, String value) {
        return Component.text(icon + " ", iconColor)
                .append(Component.text(value, NamedTextColor.WHITE));
    }

    private void update(Player p) {
        Settings s = settings(p);
        boolean visible = p.isValid() && !p.isDead()
                && p.getGameMode() != GameMode.SPECTATOR
                && !p.isInvisible();

        // One row per stat: name, hearts, money, kills, deaths
        List<Component> lines = new ArrayList<>();
        if (visible) {
            if (s.name) {
                lines.add(Component.text(p.getName(), NamedTextColor.WHITE));
            }
            if (s.hearts) {
                lines.add(line(HEART_ICON, HEART_COLOR, formatHearts(p.getHealth())));
            }
            if (s.money && economy != null) {
                lines.add(line(MONEY_ICON, MONEY_COLOR, formatMoney(economy.getBalance(p))));
            }
            if (s.kills) {
                lines.add(line(KILLS_ICON, KILLS_COLOR, String.valueOf(p.getStatistic(Statistic.PLAYER_KILLS))));
            }
            if (s.deaths) {
                lines.add(line(DEATHS_ICON, DEATHS_COLOR, String.valueOf(p.getStatistic(Statistic.DEATHS))));
            }
        }

        if (lines.isEmpty()) {
            removeDisplay(p);
            return;
        }

        TextDisplay d = displays.get(p.getUniqueId());
        if (d == null || !d.isValid() || !p.getPassengers().contains(d)) {
            if (d != null && d.isValid()) d.remove();
            d = spawnDisplay(p);
            displays.put(p.getUniqueId(), d);
        }
        d.text(Component.join(JoinConfiguration.newlines(), lines));
    }

    private TextDisplay spawnDisplay(Player p) {
        TextDisplay d = p.getWorld().spawn(p.getLocation(), TextDisplay.class, display -> {
            display.setBillboard(Display.Billboard.CENTER);
            display.setPersistent(false);
            // Raise the text a little so it floats above the head.
            // Increase the 0.25f if you want it higher, decrease to lower it.
            display.setTransformation(new Transformation(
                    new Vector3f(0f, 0.25f, 0f),
                    new AxisAngle4f(),
                    new Vector3f(1f, 1f, 1f),
                    new AxisAngle4f()));
        });
        p.addPassenger(d);
        // The owner doesn't see their own tag (just like vanilla nametags).
        p.hideEntity(this, d);
        return d;
    }

    private void removeDisplay(Player p) {
        TextDisplay d = displays.remove(p.getUniqueId());
        if (d != null && d.isValid()) d.remove();
    }

    private static final String[] MONEY_SUFFIXES = {"", "K", "M", "B", "T", "Qa", "Qi"};

    /** 999 -> 999, 1000 -> 1K, 1123 -> 1.12K, 1,000,000 -> 1M, 2,350,000,000 -> 2.35B ... */
    private String formatMoney(double amount) {
        boolean negative = amount < 0;
        double value = Math.abs(amount);

        int index = 0;
        while (value >= 1000 && index < MONEY_SUFFIXES.length - 1) {
            value /= 1000.0;
            index++;
        }

        // Truncate (not round) to 2 decimals so 999,999 shows as 999.99K instead of "1000K".
        value = Math.floor(value * 100.0) / 100.0;

        String number = String.format(Locale.US, "%.2f", value);
        if (number.contains(".")) {
            number = number.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return (negative ? "-" : "") + number + MONEY_SUFFIXES[index];
    }

    private String formatHearts(double health) {
        double hearts = health / 2.0;
        if (hearts == Math.floor(hearts)) return String.valueOf((int) hearts);
        return String.format(Locale.US, "%.1f", hearts);
    }

    // ------------------------------------------------------------------ events

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        hiddenNameTeam.addEntry(e.getPlayer().getName());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        removeDisplay(e.getPlayer());
        hiddenNameTeam.removeEntry(e.getPlayer().getName());
        saveData();
    }

    // ------------------------------------------------------------------ command + dialog

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }
        openDialog(p);
        return true;
    }

    /** Gold "ᴊᴀʟᴇʙɪ" + white "ѕᴍᴘ", side by side. No bold. */
    private Component title() {
        return Component.text(sc("JALEBI"), NamedTextColor.GOLD)
                .append(Component.text(" " + sc("SMP"), NamedTextColor.WHITE));
    }

    private void openDialog(Player player) {
        Settings s = settings(player);

        Component moneyLabel = Component.text(MONEY_ICON + " " + sc("Money"), MONEY_COLOR);
        if (economy == null) {
            moneyLabel = moneyLabel.append(Component.text(" (" + sc("Vault not found") + ")", NamedTextColor.GRAY));
        }

        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(title())
                        .canCloseWithEscape(true)
                        .body(List.of(
                                DialogBody.plainMessage(Component.text(
                                        sc("Choose what is shown above your head."), NamedTextColor.GRAY))
                        ))
                        .inputs(List.of(
                                DialogInput.bool(KEY_NAME, Component.text(sc("Username"), NamedTextColor.WHITE),
                                        s.name, "true", "false"),
                                DialogInput.bool(KEY_MONEY, moneyLabel,
                                        s.money, "true", "false"),
                                DialogInput.bool(KEY_HEARTS, Component.text(HEART_ICON + " " + sc("Hearts"), HEART_COLOR),
                                        s.hearts, "true", "false"),
                                DialogInput.bool(KEY_KILLS, Component.text(KILLS_ICON + " " + sc("Kills"), KILLS_COLOR),
                                        s.kills, "true", "false"),
                                DialogInput.bool(KEY_DEATHS, Component.text(DEATHS_ICON + " " + sc("Deaths"), DEATHS_COLOR),
                                        s.deaths, "true", "false")
                        ))
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.create(
                                Component.text(sc("Save"), NamedTextColor.GREEN),
                                Component.text(sc("Apply these settings")),
                                100,
                                DialogAction.customClick((view, audience) -> {
                                    if (!(audience instanceof Player clicker)) return;

                                    Boolean name = view.getBoolean(KEY_NAME);
                                    Boolean money = view.getBoolean(KEY_MONEY);
                                    Boolean hearts = view.getBoolean(KEY_HEARTS);
                                    Boolean kills = view.getBoolean(KEY_KILLS);
                                    Boolean deaths = view.getBoolean(KEY_DEATHS);

                                    // Make sure we touch Bukkit state on the main thread.
                                    Bukkit.getScheduler().runTask(this, () -> {
                                        Settings cs = settings(clicker);
                                        if (name != null) cs.name = name;
                                        if (money != null) cs.money = money;
                                        if (hearts != null) cs.hearts = hearts;
                                        if (kills != null) cs.kills = kills;
                                        if (deaths != null) cs.deaths = deaths;
                                        saveData();
                                        update(clicker);
                                        clicker.playSound(clicker.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.2f);
                                        clicker.sendMessage(Component.text(sc("JALEBI"), NamedTextColor.GOLD)
                                                .append(Component.text(" " + sc("SMP") + " ", NamedTextColor.WHITE))
                                                .append(Component.text(sc("Nametag settings saved."), NamedTextColor.GRAY)));
                                    });
                                }, ClickCallback.Options.builder().build())
                        ),
                        ActionButton.create(
                                Component.text(sc("Cancel"), NamedTextColor.RED),
                                Component.text(sc("Close without saving")),
                                100,
                                null // null action = just closes the dialog
                        )
                ))
        );

        player.showDialog(dialog);
    }
}
