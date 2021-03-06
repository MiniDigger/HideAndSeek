package io.indices.hideandseek.features;

import lombok.Setter;
import me.minidigger.voxelgameslib.feature.AbstractFeature;
import me.minidigger.voxelgameslib.feature.features.ScoreboardFeature;
import me.minidigger.voxelgameslib.scoreboard.Scoreboard;
import net.kyori.text.TextComponent;
import org.bukkit.ChatColor;

public class ActiveFeature extends AbstractFeature {

    @Setter
    private Scoreboard scoreboard;

    @Override
    public void init() {

    }

    @Override
    public void start() {
        getPhase().getGame().getPlayers().forEach(user -> user.sendMessage(TextComponent.of(ChatColor.RED + "Ready or not, here they come!")));
    }

    @Override
    public void stop() {

    }

    @Override
    public void tick() {

    }

    @Override
    public Class[] getDependencies() {
        return new Class[]{GameFeature.class, ScoreboardFeature.class};
    }
}
