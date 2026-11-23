package jp.tutti.optionalserverpack;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * PlaceholderAPI expansion for OptionalServerPack.
 *
 * Placeholders (per player):
 *   %optionalserverpack_status% - display text for the player's choice
 *                              (config: placeholder.on / .off / .none)
 *   %optionalserverpack_on%     - "true" if the player has the optional packs
 *                              ON, otherwise "false" (not chosen = false)
 *
 * This class is only loaded when PlaceholderAPI is installed
 * (plugin.yml declares it as a softdepend).
 */
public final class OptionalServerPackPlaceholder extends PlaceholderExpansion {

    private final OptionalServerPack plugin;

    public OptionalServerPackPlaceholder(OptionalServerPack plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "optionalserverpack";
    }

    @Override
    public @NotNull String getAuthor() {
        return "tutti";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) return "";
        switch (params.toLowerCase()) {
            case "status":
                return plugin.statusText(player.getUniqueId());
            case "on":
                return Boolean.TRUE.equals(plugin.getPreference(player.getUniqueId())) ? "true" : "false";
            default:
                return null; // unknown placeholder
        }
    }
}
