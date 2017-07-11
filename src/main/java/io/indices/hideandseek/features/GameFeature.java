package io.indices.hideandseek.features;

import com.comphenix.packetwrapper.WrapperPlayServerEntityDestroy;
import com.comphenix.packetwrapper.WrapperPlayServerMount;
import com.comphenix.packetwrapper.WrapperPlayServerSpawnEntity;
import com.comphenix.packetwrapper.WrapperPlayServerSpawnEntityLiving;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import io.indices.hideandseek.hideandseek.HideAndSeekPlayer;
import io.indices.hideandseek.util.NmsUtil;
import lombok.Setter;
import me.minidigger.voxelgameslib.VoxelGamesLib;
import me.minidigger.voxelgameslib.feature.AbstractFeature;
import me.minidigger.voxelgameslib.feature.features.ScoreboardFeature;
import me.minidigger.voxelgameslib.scoreboard.Scoreboard;
import me.minidigger.voxelgameslib.user.User;
import me.minidigger.voxelgameslib.user.UserHandler;
import net.kyori.text.TextComponent;
import net.kyori.text.format.TextColor;
import org.apache.commons.lang.time.DurationFormatUtils;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import javax.inject.Inject;
import java.util.*;
import java.util.stream.Collectors;

public class GameFeature extends AbstractFeature {

    @Inject
    private VoxelGamesLib plugin;
    @Inject
    private UserHandler userHandler;

    @Setter
    private Scoreboard scoreboard;

    private List<User> hiders = new ArrayList<>();
    private List<User> seekers = new ArrayList<>();

    private Material[] transformableBlocks = new Material[]{Material.BEACON, Material.SAND, Material.LAPIS_BLOCK};
    private Map<UUID, HideAndSeekPlayer> playerMap = new HashMap<>();
    private Map<Player, Long> movements = new WeakHashMap<>();

    @Setter
    private int ticks;
    private int nextBlock = 0;

    @Override
    public void init() {

    }

    @Override
    public Class[] getDependencies() {
        return new Class[]{ScoreboardFeature.class};
    }

    @Override
    public void start() {
        // load any existing data from previous phase(s) also using GameFeature

        Object gameStarted = getPhase().getGame().getGameData("gameStarted");
        Object hidersData = getPhase().getGame().getGameData("hiders");
        Object seekersData = getPhase().getGame().getGameData("seekers");
        Object playerMapData = getPhase().getGame().getGameData("playerMap");

        // yes, unchecked cast, the getGameData thing is dum
        // todo make getGameData non-shit

        if (gameStarted != null && (gameStarted instanceof Boolean)) {
            if(!((Boolean) gameStarted)) {
                // initialise game

                // choose seekers and hiders
                Random random = new Random();
                int playerCount = getPhase().getGame().getPlayers().size();
                int seekerAmount = (playerCount / 10) + 1; // one seeker per 10 players

                for (int i = 0; i < seekerAmount; i++) {
                    int n = random.nextInt(playerCount - 1);

                    seekers.add(getPhase().getGame().getPlayers().get(n));
                }

                getPhase().getGame().getPlayers().forEach((user) -> {
                    if (!seekers.contains(user)) {
                        Player subject = user.getPlayer();
                        HideAndSeekPlayer subjectGamePlayer = playerMap.get(subject.getUniqueId());
                        hiders.add(user);

                        assignBlock(user);

                        List<Player> clients = getPhase().getGame().getPlayers().stream()
                                .filter((u) -> !u.getUuid().equals(user.getUuid()))
                                .map(User::getPlayer)
                                .collect(Collectors.toList());

                        Bukkit.getScheduler().runTaskLater(plugin, () -> sendMorphPackets(subject, subjectGamePlayer.getBlock(), clients), 40); // run later to ensure entities spawn
                        // todo perhaps run this as clients send the packet to show that they've teleported successfully, since this makes an assumption that 40 ticks is enough

                        // good luck
                        subject.sendTitle(ChatColor.GREEN + "You have 30 seconds to hide!", null);
                    }
                });

                seekers.forEach((seeker) -> {
                    seeker.getPlayer().teleport(seeker.getPlayer().getLocation()); // todo teleport them to a locked location, dummy code for now
                    seeker.sendMessage(TextComponent.of("Start counting down from 30...").color(TextColor.GREEN)); // todo create lang leys
                });

                getPhase().getGame().putGameData("gameStarted", true);

                // save current data for other features to use
                getPhase().getGame().putGameData("hiders", hiders);
                getPhase().getGame().putGameData("seekers", seekers);
                getPhase().getGame().putGameData("playerMap", playerMap);
            }
        }

        if (hidersData != null && (hidersData instanceof List)) {
            hiders = (List<User>) hidersData;
        }

        if (seekersData != null && (seekersData instanceof List)) {
            seekers = (List<User>) seekersData;
        }

        if (playerMapData != null && (playerMapData instanceof Map)) {
            playerMap = (Map<UUID, HideAndSeekPlayer>) playerMapData;
        } else {
            // generate default values
            getPhase().getGame().getPlayers().forEach((user) -> {
                HideAndSeekPlayer hideAndSeekPlayer = new HideAndSeekPlayer();
                hideAndSeekPlayer.setUser(user);
                playerMap.put(user.getUuid(), hideAndSeekPlayer);
            });
        }

        //scoreboard.setTitle(ChatColor.AQUA + "Hide" + ChatColor.GREEN + "And" + ChatColor.YELLOW + "Seek");
        // todo improve VGL api to have string keys rather than having to edit int keys constantly
        scoreboard.createAndAddLine(7, ChatColor.RESET + "");
        scoreboard.createAndAddLine(6, ChatColor.RED + ChatColor.BOLD.toString() + "Grace Period Left");
        scoreboard.createAndAddLine(5, "" + DurationFormatUtils.formatDuration(ticks / 20 * 1000, "mm:ss"));
        scoreboard.createAndAddLine(4, ChatColor.RESET + ChatColor.RESET.toString() + "");
        scoreboard.createAndAddLine(3, ChatColor.AQUA + ChatColor.BOLD.toString() + "Players Alive");
        scoreboard.createAndAddLine(2, hiders.size() + " hiders");
        scoreboard.createAndAddLine(1, seekers.size() + "seekers");

        Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> movements.forEach((player, time) -> {
            if (time > 3 * 1000) {
                HideAndSeekPlayer hideAndSeekPlayer = playerMap.get(player.getUniqueId());
                hideAndSeekPlayer.setStationaryLocation(player.getLocation());

                // todo
                // they're ready to become a block

                // send a falling entity to the player, to make them see the block but be able to move thru it
                // to all other players, make it a solid block and destroy the falling block entity. recreate it when the player moves

                player.sendTitle(ChatColor.GREEN + "You're now a solid block!", null);
            }
        }), 0, 5);
    }

    @Override
    public void stop() {
        getPhase().getGame().putGameData("hiders", hiders);
        getPhase().getGame().putGameData("seekers", seekers);
        getPhase().getGame().putGameData("playerMap", playerMap);
    }

    @Override
    public void tick() {

    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        userHandler.getUser(event.getEntity().getUniqueId()).ifPresent((user -> {
            if (getPhase().getGame().getPlayers().contains(user)) {
                user.getPlayer().spigot().respawn();
                if (hiders.contains(user)) {
                    hiders.remove(user);
                    seekers.add(user); // you've joined the evil side now
                }
            }
        }));
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if ((event.getFrom().getBlockX() != event.getTo().getBlockX() ||
                event.getFrom().getBlockY() != event.getTo().getBlockY() ||
                event.getFrom().getBlockZ() != event.getTo().getBlockZ())) {

            HideAndSeekPlayer hideAndSeekPlayer = playerMap.get(event.getPlayer().getUniqueId());

            movements.put(event.getPlayer(), System.currentTimeMillis());

            if (hideAndSeekPlayer.isStationary()) {
                hideAndSeekPlayer.setStationaryLocation(null);

                setHiddenPlayerVisible(hideAndSeekPlayer.getUser().getPlayer());
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // wont need that data anymore
        playerMap.remove(event.getPlayer().getUniqueId());
    }

    public void setHiddenPlayerVisible(Player player) {
        // todo make them visible again
    }

    /**
     * Assigns a player with a block
     *
     * @param user the user
     */
    private void assignBlock(User user) {
        if (nextBlock > (transformableBlocks.length - 1)) {
            nextBlock = 0;
        }

        HideAndSeekPlayer hideAndSeekPlayer = playerMap.get(user.getUuid());
        hideAndSeekPlayer.setBlock(transformableBlocks[nextBlock]);

        nextBlock++;
    }

    /**
     * Send packets to 'morph' a player into a block
     *
     * @param clients list of players to send packets to
     */
    public void sendMorphPackets(Player subject, Material block, List<Player> clients) {
        clients.forEach((client) -> sendMorphPackets(subject, block, client));
    }

    /**
     * Send packets to 'morph' a player into a block
     *
     * @param client player to send packets to
     */
    public void sendMorphPackets(Player subject, Material block, Player client) {
        int playerEntityId = subject.getEntityId();

        // i would rather use NMS, but i was convinced to use protocollib
        // welcome to the evil side, i suppose

        // destroy the real player, client side
        WrapperPlayServerEntityDestroy destroyPacket = new WrapperPlayServerEntityDestroy();
        destroyPacket.setEntityIds(new int[]{playerEntityId});

        // create our fake entity to become the player so block movement remains smooth
        // looks like server doesn't smoothly track non-living entities
        WrapperPlayServerSpawnEntityLiving morphedEntityPacket = new WrapperPlayServerSpawnEntityLiving();
        morphedEntityPacket.setEntityID(playerEntityId);
        morphedEntityPacket.setUniqueId(UUID.randomUUID());
        morphedEntityPacket.setType(EntityType.SLIME);

        Location location = subject.getLocation();
        morphedEntityPacket.setX(location.getX());
        morphedEntityPacket.setY(location.getY());
        morphedEntityPacket.setZ(location.getZ());
        morphedEntityPacket.setPitch(location.getPitch());
        morphedEntityPacket.setYaw(location.getYaw());

        WrappedDataWatcher morphedEntityDataWatcher = new WrappedDataWatcher();
        //morphedEntityDataWatcher.setObject(0, WrappedDataWatcher.Registry.get(Byte.class), (byte) 0x20, true); // set invisible
        morphedEntityDataWatcher.setObject(4, WrappedDataWatcher.Registry.get(Boolean.class), true, true);
        morphedEntityDataWatcher.setObject(5, WrappedDataWatcher.Registry.get(Boolean.class), true, true); // no gravity
        morphedEntityDataWatcher.setObject(12, WrappedDataWatcher.Registry.get(Integer.class), 0, true); // Set size
        morphedEntityPacket.setMetadata(morphedEntityDataWatcher);

        // create our falling block they are 'disguised' as
        WrapperPlayServerSpawnEntity blockSpawnPacket = new WrapperPlayServerSpawnEntity();
        int blockEntityId = NmsUtil.getNextEntityId();
        blockSpawnPacket.setEntityID(blockEntityId);
        blockSpawnPacket.setUniqueId(UUID.randomUUID());
        blockSpawnPacket.setType(70);
        blockSpawnPacket.setObjectData(block.getId());

        blockSpawnPacket.setX(location.getX());
        blockSpawnPacket.setY(location.getY());
        blockSpawnPacket.setZ(location.getZ());
        blockSpawnPacket.setPitch(location.getPitch());
        blockSpawnPacket.setYaw(location.getYaw());

        // set the falling block as a passenger of the fake entity
        WrapperPlayServerMount mount = new WrapperPlayServerMount();
        mount.setEntityID(subject.getEntityId());
        mount.setPassengerIds(new int[]{blockEntityId});

        // send packets to client
        destroyPacket.sendPacket(client);
        morphedEntityPacket.sendPacket(client);
        blockSpawnPacket.sendPacket(client);
        mount.sendPacket(client);
    }
}