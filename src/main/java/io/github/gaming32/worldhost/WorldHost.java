package io.github.gaming32.worldhost;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import io.github.gaming32.worldhost.protocol.ProtocolClient;
import io.github.gaming32.worldhost.upnp.Gateway;
import io.github.gaming32.worldhost.upnp.GatewayFinder;
import io.github.gaming32.worldhost.upnp.UPnPErrors;
import io.github.gaming32.worldhost.versions.Components;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.players.GameProfileCache;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;
import org.quiltmc.json5.JsonReader;
import org.quiltmc.json5.JsonWriter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static net.minecraft.commands.Commands.literal;

//#if MC >= 11700
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
//#else
//$$ import org.apache.logging.log4j.LogManager;
//$$ import org.apache.logging.log4j.Logger;
//#endif

//#if MC >= 11902
import io.github.gaming32.worldhost.mixin.MinecraftAccessor;
import net.minecraft.server.Services;
//#else
//$$ import com.mojang.authlib.minecraft.MinecraftProfileTexture;
//$$ import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
//$$ import net.minecraft.client.resources.DefaultPlayerSkin;
//$$ import net.minecraft.world.entity.player.Player;
//#endif

//#if FABRIC
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
//#else
//$$ import io.github.gaming32.worldhost.gui.WorldHostConfigScreen;
//$$ import net.minecraft.client.gui.screens.Screen;
//$$ import net.minecraft.server.packs.PackType;
//$$ import net.minecraftforge.api.distmarker.Dist;
//$$ import net.minecraftforge.eventbus.api.SubscribeEvent;
//$$ import net.minecraftforge.fml.ModLoadingContext;
//$$ import net.minecraftforge.fml.common.Mod;
//$$ import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
//$$ import java.util.function.BiFunction;
//#if MC >= 11902
//$$ import net.minecraftforge.client.ConfigScreenHandler;
//#elseif MC >= 11802
//$$ import net.minecraftforge.client.ConfigGuiHandler;
//#else
//$$ import net.minecraftforge.fml.ExtensionPoint;
//#endif
//#if MC > 11605
//$$ import net.minecraftforge.resource.ResourcePackLoader;
//#else
//$$ import net.minecraftforge.fml.packs.ResourcePackLoader;
//#endif
//#endif

//#if FORGE
//$$ @Mod(WorldHost.MOD_ID)
//#endif
public class WorldHost
    //#if FABRIC
    implements ClientModInitializer
    //#endif
{
    public static final String MOD_ID =
        //#if FORGE
        //$$ "world_host";
        //#else
        "world-host";
        //#endif

    public static final Logger LOGGER =
        //#if MC >= 11700
        LogUtils.getLogger();
        //#else
        //$$ LogManager.getLogger();
        //#endif

    public static final File GAME_DIR = Minecraft.getInstance().gameDirectory;
    public static final File CACHE_DIR = new File(GAME_DIR, ".world-host-cache");

    public static final File CONFIG_DIR = new File(GAME_DIR, "config");
    public static final Path CONFIG_FILE = new File(CONFIG_DIR, "world-host.json5").toPath();
    public static final Path OLD_CONFIG_FILE = new File(CONFIG_DIR, "world-host.json").toPath();
    public static final WorldHostConfig CONFIG = new WorldHostConfig();

    public static final List<String> WORDS_FOR_CID =
        //#if FABRIC
        FabricLoader.getInstance()
            .getModContainer(MOD_ID)
            .flatMap(c -> c.findPath("assets/world-host/16k.txt"))
            .map(path -> {
                try {
                    return Files.lines(path, StandardCharsets.US_ASCII);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            })
        //#else
        //$$ ResourcePackLoader
            //#if MC > 11605
            //$$ .getPackFor(MOD_ID)
            //#else
            //$$ .getResourcePackFor(MOD_ID)
            //#endif
        //$$     .map(c -> {
                //#if MC <= 11902
                //$$ try {
                //#endif
        //$$             return c.getResource(PackType.CLIENT_RESOURCES, new ResourceLocation("world-host", "16k"));
                //#if MC <= 11902
                //$$ } catch (IOException e) {
                //$$     throw new UncheckedIOException(e);
                //$$ }
                //#endif
        //$$     })
            //#if MC > 11902
            //$$ .map(i -> {
            //$$     try {
            //$$         return i.get();
            //$$     } catch (IOException e) {
            //$$         throw new UncheckedIOException(e);
            //$$     }
            //$$ })
            //#endif
        //$$     .map(is -> new InputStreamReader(is, StandardCharsets.US_ASCII))
        //$$     .map(BufferedReader::new)
        //$$     .map(BufferedReader::lines)
        //#endif
            .orElseThrow(() -> new IllegalStateException("Unable to find 16k.txt"))
            .filter(s -> !s.startsWith("//"))
            .toList();

    public static final long MAX_CONNECTION_IDS = 1L << 42;

    public static final Set<UUID> ONLINE_FRIENDS = new HashSet<>();
    public static final Map<UUID, ServerStatus> ONLINE_FRIEND_PINGS = new HashMap<>();
    public static final Set<FriendsListUpdate> ONLINE_FRIEND_UPDATES = Collections.newSetFromMap(new WeakHashMap<>());

    public static final Long2ObjectMap<ProxyClient> CONNECTED_PROXY_CLIENTS = new Long2ObjectOpenHashMap<>();

    public static final long CONNECTION_ID = new SecureRandom().nextLong(MAX_CONNECTION_IDS);

    public static Gateway upnpGateway;

    private static GameProfileCache profileCache;

    public static ProtocolClient protoClient;
    private static long lastReconnectTime;
    private static Future<Void> connectingFuture;

    //#if FABRIC
    @Override
    public void onInitializeClient() {
        init();
    }
    //#endif

    private static void init() {
        LOGGER.info("Using client-generated connection ID {}", connectionIdToString(CONNECTION_ID));

        loadConfig();

        //noinspection ResultOfMethodCallIgnored
        CACHE_DIR.mkdirs();
        //#if MC >= 11902
        profileCache = Services.create(
            ((MinecraftAccessor)Minecraft.getInstance()).getAuthenticationService(),
            CACHE_DIR
        ).profileCache();
        //#else
        //$$ profileCache = new GameProfileCache(
        //$$     new YggdrasilAuthenticationService(
        //$$         Minecraft.getInstance().getProxy()
                //#if MC <= 11601
                //$$ , UUID.randomUUID().toString()
                //#endif
        //$$     )
        //$$         .createProfileRepository(),
        //$$     new File(CACHE_DIR, "usercache.json")
        //$$ );
        //#endif
        //#if MC > 11605
        profileCache.setExecutor(Util.backgroundExecutor());
        //#endif

        reconnect(false, true);

        new GatewayFinder(gateway -> {
            upnpGateway = gateway;
            LOGGER.info("Found UPnP gateway: {}", gateway.getGatewayIP());
        });
    }

    public static void loadConfig() {
        try (JsonReader reader = JsonReader.json5(CONFIG_FILE)) {
            CONFIG.read(reader);
            if (Files.exists(OLD_CONFIG_FILE)) {
                LOGGER.info("Old {} still exists. Maybe consider removing it?", OLD_CONFIG_FILE.getFileName());
            }
        } catch (NoSuchFileException e) {
            LOGGER.info("{} not found. Trying to load old {}.", CONFIG_FILE.getFileName(), OLD_CONFIG_FILE.getFileName());
            try (JsonReader reader = JsonReader.json(OLD_CONFIG_FILE)) {
                CONFIG.read(reader);
                LOGGER.info(
                    "Found and read old {} into new {}. Maybe consider deleting the old {}?",
                    OLD_CONFIG_FILE.getFileName(), CONFIG_FILE.getFileName(), OLD_CONFIG_FILE.getFileName()
                );
            } catch (NoSuchFileException e1) {
                LOGGER.info("Old {} not found. Writing default config.", OLD_CONFIG_FILE.getFileName());
            } catch (IOException e1) {
                LOGGER.error("Failed to load old {}.", OLD_CONFIG_FILE.getFileName(), e1);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to load {}.", CONFIG_FILE.getFileName(), e);
        }
        saveConfig();
    }

    public static void saveConfig() {
        try (JsonWriter writer = JsonWriter.json5(CONFIG_FILE)) {
            CONFIG.write(writer);
        } catch (IOException e) {
            LOGGER.error("Failed to write {}.", CONFIG_FILE.getFileName(), e);
        }
    }

    public static void tickHandler(Minecraft minecraft) {
        if (protoClient == null || protoClient.isClosed()) {
            if (protoClient != null) {
                protoClient = null;
            }
            connectingFuture = null;
            final long time = Util.getMillis();
            if (time - lastReconnectTime > 20_000) {
                lastReconnectTime = time;
                reconnect(CONFIG.isEnableReconnectionToasts(), false);
            }
        }
        if (connectingFuture != null && connectingFuture.isDone()) {
            connectingFuture = null;
            LOGGER.info("Finished authenticating with WS server. Requesting friends list.");
            ONLINE_FRIENDS.clear();
            protoClient.listOnline(CONFIG.getFriends());
            final IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
            if (server != null && server.isPublished()) {
                protoClient.publishedWorld(CONFIG.getFriends());
            }
        }
    }

    public static void commandRegistrationHandler(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(literal("worldhost")
            .then(literal("ip")
                .requires(s -> s.getServer().isPublished())
                .executes(WorldHost::ipCommand)
            )
            .then(literal("tempip")
                .requires(s -> s.getServer().isPublished())
                .executes(ctx -> {
                    if (upnpGateway != null && protoClient != null && !protoClient.getUserIp().isEmpty()) {
                        try {
                            final int port = ctx.getSource().getServer().getPort();
                            final UPnPErrors.AddPortMappingErrors error = upnpGateway.openPort(port, 60, false);
                            if (error == null) {
                                ctx.getSource().sendSuccess(
                                    Components.translatable(
                                        "world-host.worldhost.tempip.success",
                                        Components.copyOnClickText(protoClient.getUserIp() + ':' + port)
                                    ),
                                    false
                                );
                                return Command.SINGLE_SUCCESS;
                            }
                            WorldHost.LOGGER.info("Failed to use UPnP mode due to {}. Falling back to Proxy mode.", error);
                        } catch (Exception e) {
                            WorldHost.LOGGER.error("Failed to open UPnP due to exception", e);
                        }
                    }
                    return ipCommand(ctx);
                })
            )
        );
    }

    public static void reconnect(boolean successToast, boolean failureToast) {
        if (protoClient != null) {
            protoClient.close();
            protoClient = null;
        }
        final UUID uuid = Minecraft.getInstance().getUser().getGameProfile().getId();
        if (uuid == null) {
            LOGGER.warn("Failed to get player UUID. Unable to use World Host.");
            if (failureToast) {
                DeferredToastManager.show(
                    SystemToast.SystemToastIds.TUTORIAL_HINT,
                    Components.translatable("world-host.wh_connect.not_available"),
                    null
                );
            }
            return;
        }
        LOGGER.info("Attempting to connect to WH server at {}", CONFIG.getServerIp());
        protoClient = new ProtocolClient(CONFIG.getServerIp(), successToast, failureToast);
        connectingFuture = protoClient.getConnectingFuture();
        protoClient.authenticate(uuid);
    }

    public static String getName(GameProfile profile) {
        return getIfBlank(profile.getName(), () -> profile.getId().toString());
    }

    // From Apache Commons Lang StringUtils 3.10+
    public static <T extends CharSequence> T getIfBlank(final T str, final Supplier<T> defaultSupplier) {
        return StringUtils.isBlank(str) ? defaultSupplier == null ? null : defaultSupplier.get() : str;
    }

    public static GameProfileCache getProfileCache() {
        return profileCache;
    }

    public static ResourceLocation getInsecureSkinLocation(GameProfile gameProfile) {
        final SkinManager skinManager = Minecraft.getInstance().getSkinManager();
        //#if MC >= 11902
        return skinManager.getInsecureSkinLocation(gameProfile);
        //#else
        //$$ final MinecraftProfileTexture texture = skinManager.getInsecureSkinInformation(gameProfile)
        //$$     .get(MinecraftProfileTexture.Type.SKIN);
        //$$ return texture != null
        //$$     ? skinManager.registerTexture(texture, MinecraftProfileTexture.Type.SKIN)
        //$$     : DefaultPlayerSkin.getDefaultSkin(Player.createPlayerUUID(gameProfile));
        //#endif
    }

    public static void getMaybeAsync(GameProfileCache cache, String name, Consumer<Optional<GameProfile>> action) {
        //#if MC > 11605
        cache.getAsync(name, action);
        //#else
        //$$ action.accept(Optional.ofNullable(cache.get(name)));
        //#endif
    }

    public static void positionTexShader() {
        //#if MC > 11605
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        //#endif
    }

    public static void texture(ResourceLocation texture) {
        //#if MC > 11605
        RenderSystem.setShaderTexture(0, texture);
        //#else
        //$$ Minecraft.getInstance().getTextureManager().bind(texture);
        //#endif
    }

    //#if MC <= 11605
    //$$ @SuppressWarnings("deprecation")
    //#endif
    public static void color(float r, float g, float b, float a) {
        RenderSystem.
            //#if MC > 11605
            setShaderColor
            //#else
            //$$ color4f
            //#endif
                (r, g, b, a);
    }

    public static boolean isFriend(UUID user) {
        return CONFIG.isEnableFriends() && CONFIG.getFriends().contains(user);
    }

    public static void showProfileToast(UUID user, String title, Component description) {
        Util.backgroundExecutor().execute(() -> {
            final GameProfile profile = Minecraft.getInstance()
                .getMinecraftSessionService()
                .fillProfileProperties(new GameProfile(user, null), false);
            Minecraft.getInstance().execute(() -> {
                final ResourceLocation skinTexture = getInsecureSkinLocation(profile);
                DeferredToastManager.show(
                    SystemToast.SystemToastIds.WORLD_ACCESS_FAILURE,
                    (matrices, x, y) -> {
                        texture(skinTexture);
                        RenderSystem.enableBlend();
                        GuiComponent.blit(matrices, x, y, 20, 20, 8, 8, 8, 8, 64, 64);
                        GuiComponent.blit(matrices, x, y, 20, 20, 40, 8, 8, 8, 64, 64);
                    },
                    Components.translatable(title, getName(profile)),
                    description
                );
            });
        });
    }

    public static FriendlyByteBuf createByteBuf() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    @SuppressWarnings("RedundantThrows")
    public static ServerStatus parseServerStatus(FriendlyByteBuf buf) throws IOException {
        //#if MC > 11605
        return new ClientboundStatusResponsePacket(buf)
            //#if MC >= 11904
            .status();
            //#else
            //$$ .getStatus();
            //#endif
        //#else
        //$$ final ClientboundStatusResponsePacket packet = new ClientboundStatusResponsePacket();
        //$$ packet.read(buf);
        //$$ return packet.getStatus();
        //#endif
    }

    public static ServerStatus createEmptyServerStatus() {
        //#if MC >= 11904
        return new ServerStatus(
            Components.EMPTY, Optional.empty(), Optional.empty(), Optional.empty(), false
            //#if FORGE
            //$$ , Optional.empty()
            //#endif
        );
        //#else
        //$$ return new ServerStatus();
        //#endif
    }

    @Nullable
    public static String getExternalIp() {
        if (protoClient.getBaseIp().isEmpty()) {
            return null;
        }
        String ip = connectionIdToString(protoClient.getConnectionId()) + '.' + protoClient.getBaseIp();
        if (protoClient.getBasePort() != 25565) {
            ip += ":" + protoClient.getBasePort();
        }
        return ip;
    }

    public static void pingFriends() {
        ONLINE_FRIEND_PINGS.clear();
        if (protoClient != null) {
            protoClient.queryRequest(CONFIG.getFriends());
        }
    }

    public static String connectionIdToString(long connectionId) {
        if (connectionId < 0 || connectionId >= MAX_CONNECTION_IDS) {
            throw new IllegalArgumentException("Invalid connection ID " + connectionId);
        }
        final int first = (int)(connectionId & 0x3fff);
        final int second = (int)(connectionId >>> 14) & 0x3fff;
        final int third = (int)(connectionId >>> 28) & 0x3fff;
        return WORDS_FOR_CID.get(first) + '-' +
            WORDS_FOR_CID.get(second) + '-' +
            WORDS_FOR_CID.get(third);
    }

    private static int ipCommand(CommandContext<CommandSourceStack> ctx) {
        if (protoClient == null) {
            ctx.getSource().sendFailure(Components.translatable("world-host.worldhost.ip.not_connected"));
            return 0;
        }
        final String externalIp = getExternalIp();
        if (externalIp == null) {
            ctx.getSource().sendFailure(Components.translatable("world-host.worldhost.ip.no_server_support"));
            return 0;
        }
        ctx.getSource().sendSuccess(
            Components.translatable(
                "world-host.worldhost.ip.success",
                Components.copyOnClickText(externalIp)
            ),
            false
        );
        return Command.SINGLE_SUCCESS;
    }

    //#if FORGE
    //$$ @Mod.EventBusSubscriber(modid = MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    //$$ public static class ClientModEvents {
    //$$     @SubscribeEvent
    //$$     public static void onClientSetup(FMLClientSetupEvent event) {
    //$$         init();
    //$$         final BiFunction<Minecraft, Screen, Screen> screenFunction =
    //$$             (mc, screen) -> new WorldHostConfigScreen(screen);
    //$$         ModLoadingContext.get().registerExtensionPoint(
                //#if MC >= 11902
                //$$ ConfigScreenHandler.ConfigScreenFactory.class,
                //$$ () -> new ConfigScreenHandler.ConfigScreenFactory(screenFunction)
                //#elseif MC >= 11802
                //$$ ConfigGuiHandler.ConfigGuiFactory.class,
                //$$ () -> new ConfigGuiHandler.ConfigGuiFactory(screenFunction)
                //#else
                //$$ ExtensionPoint.CONFIGGUIFACTORY, () -> screenFunction
                //#endif
    //$$         );
    //$$     }
    //$$ }
    //#endif
}
