package com.hibiscusmc.hmccosmetics.user;

import com.hibiscusmc.hmccosmetics.HMCCosmeticsPlugin;
import com.hibiscusmc.hmccosmetics.config.Settings;
import com.hibiscusmc.hmccosmetics.config.WardrobeSettings;
import com.hibiscusmc.hmccosmetics.cosmetic.CosmeticSlot;
import com.hibiscusmc.hmccosmetics.nms.NMSHandlers;
import com.hibiscusmc.hmccosmetics.util.MessagesUtil;
import com.hibiscusmc.hmccosmetics.util.ServerUtils;
import com.hibiscusmc.hmccosmetics.util.packets.PacketManager;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public class Wardrobe {

    private int NPC_ID;
    private String npcName;
    private UUID WARDROBE_UUID;
    private int ARMORSTAND_ID;
    private GameMode originalGamemode;
    private CosmeticUser VIEWER;
    private Location viewingLocation;
    private Location npcLocation;
    private Location exitLocation;
    private BossBar bossBar;
    private boolean active;
    private WardrobeStatus wardrobeStatus;

    public Wardrobe(CosmeticUser user) {
        NPC_ID = NMSHandlers.getHandler().getNextEntityId();
        ARMORSTAND_ID = NMSHandlers.getHandler().getNextEntityId();
        WARDROBE_UUID = UUID.randomUUID();
        VIEWER = user;
        wardrobeStatus = WardrobeStatus.SETUP;
    }

    public void start() {
        setWardrobeStatus(WardrobeStatus.STARTING);
        Player player = VIEWER.getPlayer();

        this.originalGamemode = player.getGameMode();
        if (WardrobeSettings.isReturnLastLocation()) {
            this.exitLocation = player.getLocation().clone();
        } else {
            this.exitLocation = WardrobeSettings.getLeaveLocation();
        }

        viewingLocation = WardrobeSettings.getViewerLocation();
        npcLocation = WardrobeSettings.getWardrobeLocation();

        VIEWER.hidePlayer();
        List<Player> viewer = List.of(player);
        List<Player> outsideViewers = PacketManager.getViewers(viewingLocation);
        outsideViewers.remove(player);

        MessagesUtil.sendMessage(player, "opened-wardrobe");

        Runnable run = () -> {
            // Armorstand
            PacketManager.sendEntitySpawnPacket(viewingLocation, ARMORSTAND_ID, EntityType.ARMOR_STAND, UUID.randomUUID(), viewer);
            PacketManager.sendInvisibilityPacket(ARMORSTAND_ID, viewer);
            PacketManager.sendLookPacket(ARMORSTAND_ID, viewingLocation, viewer);

            // Player
            PacketManager.gamemodeChangePacket(player, 3);
            PacketManager.sendCameraPacket(ARMORSTAND_ID, viewer);

            // NPC
            npcName = "WardrobeNPC-" + NPC_ID;
            while (npcName.length() > 16) {
                npcName = npcName.substring(16);
            }
            PacketManager.sendFakePlayerInfoPacket(player, NPC_ID, WARDROBE_UUID, npcName, viewer);

            // NPC 2
            Bukkit.getScheduler().runTaskLater(HMCCosmeticsPlugin.getInstance(), () -> {
                PacketManager.sendFakePlayerSpawnPacket(npcLocation, WARDROBE_UUID, NPC_ID, viewer);
                MessagesUtil.sendDebugMessages("Spawned Fake Player on " + npcLocation);
                NMSHandlers.getHandler().hideNPCName(player, npcName);
            }, 4);

            // Location
            PacketManager.sendLookPacket(NPC_ID, npcLocation, viewer);
            PacketManager.sendRotationPacket(NPC_ID, npcLocation, true, viewer);

            // Misc
            if (VIEWER.hasCosmeticInSlot(CosmeticSlot.BACKPACK)) {
                PacketManager.ridingMountPacket(NPC_ID, VIEWER.getBackpackEntity().getEntityId(), viewer);
            }

            if (VIEWER.hasCosmeticInSlot(CosmeticSlot.BALLOON)) {
                PacketManager.sendLeashPacket(VIEWER.getBalloonEntity().getPufferfishBalloonId(), -1, viewer);
                PacketManager.sendLeashPacket(VIEWER.getBalloonEntity().getPufferfishBalloonId(), NPC_ID, viewer); // This needs a possible fix
                //PacketManager.sendLeashPacket(VIEWER.getBalloonEntity().getModelId(), NPC_ID, viewer);

                PacketManager.sendTeleportPacket(VIEWER.getBalloonEntity().getPufferfishBalloonId(), npcLocation.clone().add(Settings.getBalloonOffset()), false, viewer);
                PacketManager.sendTeleportPacket(VIEWER.getBalloonEntity().getModelId(), npcLocation.clone().add(Settings.getBalloonOffset()), false, viewer);
            }

            if (WardrobeSettings.getEnabledBossbar()) {
                float progress = WardrobeSettings.getBossbarProgress();
                Component message = MessagesUtil.processStringNoKey(WardrobeSettings.getBossbarText());

                bossBar = BossBar.bossBar(message, progress, WardrobeSettings.getBossbarColor(), WardrobeSettings.getBossbarOverlay());
                Audience target = BukkitAudiences.create(HMCCosmeticsPlugin.getInstance()).player(player);

                target.showBossBar(bossBar);
            }

            this.active = true;
            update();
            setWardrobeStatus(WardrobeStatus.RUNNING);
        };


        if (WardrobeSettings.isEnabledTransition()) {
            MessagesUtil.sendTitle(
                    VIEWER.getPlayer(),
                    WardrobeSettings.getTransitionText(),
                    WardrobeSettings.getTransitionFadeIn(),
                    WardrobeSettings.getTransitionStay(),
                    WardrobeSettings.getTransitionFadeOut()
            );
            Bukkit.getScheduler().runTaskLater(HMCCosmeticsPlugin.getInstance(), run, WardrobeSettings.getTransitionDelay());
        } else {
            run.run();
        }

    }

    public void end() {
        setWardrobeStatus(WardrobeStatus.STOPPING);
        Player player = VIEWER.getPlayer();

        List<Player> viewer = List.of(player);
        List<Player> outsideViewers = PacketManager.getViewers(viewingLocation);
        outsideViewers.remove(player);

        MessagesUtil.sendMessage(player, "closed-wardrobe");

        Runnable run = () -> {
            this.active = false;

            // NPC
            if (VIEWER.hasCosmeticInSlot(CosmeticSlot.BALLOON)) PacketManager.sendLeashPacket(VIEWER.getBalloonEntity().getModelId(), -1, viewer);
            PacketManager.sendEntityDestroyPacket(NPC_ID, viewer); // Success
            PacketManager.sendRemovePlayerPacket(player, WARDROBE_UUID, viewer); // Success

            // Player
            PacketManager.sendCameraPacket(player.getEntityId(), viewer);
            PacketManager.gamemodeChangePacket(player, ServerUtils.convertGamemode(this.originalGamemode)); // Success

            // Armorstand
            PacketManager.sendEntityDestroyPacket(ARMORSTAND_ID, viewer); // Sucess

            //PacketManager.sendEntityDestroyPacket(player.getEntityId(), viewer); // Success
            player.setGameMode(this.originalGamemode);
            VIEWER.showPlayer();

            if (VIEWER.hasCosmeticInSlot(CosmeticSlot.BACKPACK)) {
                VIEWER.respawnBackpack();
                //PacketManager.ridingMountPacket(player.getEntityId(), VIEWER.getBackpackEntity().getEntityId(), viewer);
            }

            if (VIEWER.hasCosmeticInSlot(CosmeticSlot.BALLOON)) {
                VIEWER.respawnBalloon();
                //PacketManager.sendLeashPacket(VIEWER.getBalloonEntity().getPufferfishBalloonId(), player.getEntityId(), viewer);
            }

            if (exitLocation == null) {
                player.teleport(player.getWorld().getSpawnLocation());
            } else {
                player.teleport(exitLocation);
            }

            if (WardrobeSettings.isEquipPumpkin()) {
                NMSHandlers.getHandler().equipmentSlotUpdate(VIEWER.getPlayer().getEntityId(), EquipmentSlot.HEAD, player.getInventory().getHelmet(), viewer);
            }

            if (WardrobeSettings.getEnabledBossbar()) {
                Audience target = BukkitAudiences.create(HMCCosmeticsPlugin.getInstance()).player(player);

                target.hideBossBar(bossBar);
            }

            VIEWER.updateCosmetic();
        };
        run.run();
    }

    public void update() {
        final AtomicInteger data = new AtomicInteger();

        BukkitRunnable runnable = new BukkitRunnable() {
            @Override
            public void run() {
                if (active == false) {
                    MessagesUtil.sendDebugMessages("Active is false");
                    this.cancel();
                    return;
                }
                MessagesUtil.sendDebugMessages("Update ");
                List<Player> viewer = List.of(VIEWER.getPlayer());
                List<Player> outsideViewers = PacketManager.getViewers(viewingLocation);
                outsideViewers.remove(VIEWER.getPlayer());

                Location location = WardrobeSettings.getWardrobeLocation().clone();
                int yaw = data.get();
                location.setYaw(yaw);

                PacketManager.sendLookPacket(NPC_ID, location, viewer);
                VIEWER.hidePlayer();
                int rotationSpeed = WardrobeSettings.getRotationSpeed();
                location.setYaw(getNextYaw(yaw - 30, rotationSpeed));
                PacketManager.sendRotationPacket(NPC_ID, location, true, viewer);
                int nextyaw = getNextYaw(yaw, rotationSpeed);
                data.set(nextyaw);

                for (CosmeticSlot slot : CosmeticSlot.values()) {
                    PacketManager.equipmentSlotUpdate(NPC_ID, VIEWER, slot, viewer);
                }

                if (VIEWER.hasCosmeticInSlot(CosmeticSlot.BACKPACK)) {
                    PacketManager.sendTeleportPacket(VIEWER.getArmorstandId(), location, false, viewer);
                    PacketManager.ridingMountPacket(NPC_ID, VIEWER.getBackpackEntity().getEntityId(), viewer);
                    VIEWER.getBackpackEntity().setRotation(nextyaw, 0);
                    PacketManager.sendEntityDestroyPacket(VIEWER.getArmorstandId(), outsideViewers);
                }

                if (VIEWER.hasCosmeticInSlot(CosmeticSlot.BALLOON)) {
                    PacketManager.sendTeleportPacket(VIEWER.getBalloonEntity().getPufferfishBalloonId(), WardrobeSettings.getWardrobeLocation().add(Settings.getBalloonOffset()), false, viewer);
                    VIEWER.getBalloonEntity().getModelEntity().teleport(WardrobeSettings.getWardrobeLocation().add(Settings.getBalloonOffset()));
                    PacketManager.sendLeashPacket(VIEWER.getBalloonEntity().getPufferfishBalloonId(), -1, outsideViewers);
                    PacketManager.sendEntityDestroyPacket(VIEWER.getBalloonEntity().getModelId(), outsideViewers);
                    //PacketManager.sendLeashPacket(VIEWER.getBalloonEntity().getModelId(), NPC_ID, viewer); // Pufferfish goes away for some reason?
                }

                if (WardrobeSettings.isEquipPumpkin()) {
                    NMSHandlers.getHandler().equipmentSlotUpdate(VIEWER.getPlayer().getEntityId(), EquipmentSlot.HEAD, new ItemStack(Material.CARVED_PUMPKIN), viewer);
                }
            }
        };

        runnable.runTaskTimer(HMCCosmeticsPlugin.getInstance(), 0, 2);
    }

    private static int getNextYaw(final int current, final int rotationSpeed) {
        int nextYaw = current + rotationSpeed;
        if (nextYaw > 179) {
            nextYaw = (current + rotationSpeed) - 358;
            return nextYaw;
        }
        return nextYaw;
    }

    public int getArmorstandId() {
        return ARMORSTAND_ID;
    }

    public WardrobeStatus getWardrobeStatus() {
        return wardrobeStatus;
    }

    public void setWardrobeStatus(WardrobeStatus status) {
        this.wardrobeStatus = status;
    }

    public enum WardrobeStatus {
        SETUP,
        STARTING,
        RUNNING,
        STOPPING,
    }
}