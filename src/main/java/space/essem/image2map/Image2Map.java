package space.essem.image2map;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.logging.LogUtils;
import eu.pb4.sgui.api.GuiHelpers;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import space.essem.image2map.config.Image2MapConfig;
import space.essem.image2map.gui.PreviewGui;
import space.essem.image2map.renderer.MapRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URL;
import java.net.URLConnection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class Image2Map implements ModInitializer {
    public static final Logger LOGGER = LogUtils.getLogger();

    public static Image2MapConfig CONFIG = Image2MapConfig.loadOrCreateConfig();

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("image2map")
                    .requires(source -> source.hasPermissionLevel(CONFIG.minPermLevel))
                    .then(literal("create")
                            .then(argument("width", IntegerArgumentType.integer(1))
                                    .then(argument("height", IntegerArgumentType.integer(1))
                                            .then(argument("mode", StringArgumentType.word()).suggests(new DitherModeSuggestionProvider())
                                                    .then(argument("path", StringArgumentType.greedyString())
                                                            .executes(this::createMap))
                                            )
                                    )
                            )
                            .then(argument("mode", StringArgumentType.word()).suggests(new DitherModeSuggestionProvider())
                                    .then(argument("path", StringArgumentType.greedyString())
                                            .executes(this::createMap)
                                    )
                            )
                    )
                    .then(literal("preview")
                            .then(argument("path", StringArgumentType.greedyString())
                                    .executes(this::openPreview)
                            )
                    )
            );
        });
    }

    private int openPreview(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        String input = StringArgumentType.getString(context, "path");

        source.sendFeedback(Text.literal("Getting image...").formatted(Formatting.YELLOW), false);

        getImage(input).orTimeout(60, TimeUnit.SECONDS).handleAsync((image, ex) -> {
            if (image == null || ex != null) {
                source.sendFeedback(Text.literal("That doesn't seem to be a valid image!").formatted(Formatting.RED), false);
            }

            if (GuiHelpers.getCurrentGui(source.getPlayer()) instanceof PreviewGui previewGui) {
                previewGui.close();
            }
            new PreviewGui(context.getSource().getPlayer(), image, input, DitherMode.NONE, image.getWidth(), image.getHeight());

            return null;
        }, source.getServer());

        return 1;
    }

    class DitherModeSuggestionProvider implements SuggestionProvider<ServerCommandSource> {

        @Override
        public CompletableFuture<Suggestions> getSuggestions(CommandContext<ServerCommandSource> context,
                                                             SuggestionsBuilder builder) throws CommandSyntaxException {
            builder.suggest("none");
            builder.suggest("dither");
            return builder.buildFuture();
        }

    }

    public enum DitherMode {
        NONE,
        FLOYD;

        public static DitherMode fromString(String string) {
            if (string.equalsIgnoreCase("NONE"))
                return DitherMode.NONE;
            else if (string.equalsIgnoreCase("DITHER") || string.equalsIgnoreCase("FLOYD"))
                return DitherMode.FLOYD;
            throw new IllegalArgumentException("invalid dither mode");
        }
    }

    private CompletableFuture<BufferedImage> getImage(String input) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (isValid(input)) {
                    URL url = new URL(input);
                    URLConnection connection = url.openConnection();
                    connection.setRequestProperty("User-Agent", "Image2Map mod");
                    connection.connect();
                    return ImageIO.read(connection.getInputStream());
                } else if (CONFIG.allowLocalFiles) {
                    File file = new File(input);
                    return ImageIO.read(file);
                } else {
                    return null;
                }
            } catch (Throwable e) {
                return null;
            }
        });
    }

    private int createMap(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();

        PlayerEntity player = source.getPlayer();
        DitherMode mode;
        String modeStr = StringArgumentType.getString(context, "mode");
        try {
            mode = DitherMode.fromString(modeStr);
        } catch (IllegalArgumentException e) {
            throw new SimpleCommandExceptionType(() -> "Invalid dither mode '" + modeStr + "'").create();
        }

        String input = StringArgumentType.getString(context, "path");

        source.sendFeedback(Text.literal("Getting image...").formatted(Formatting.YELLOW), false);

        getImage(input).orTimeout(60, TimeUnit.SECONDS).handleAsync((image, ex) -> {
            if (image == null || ex != null) {
                source.sendFeedback(Text.literal("That doesn't seem to be a valid image!").formatted(Formatting.RED), false);
            }

            int width;
            int height;

            try {
                width = IntegerArgumentType.getInteger(context, "width");
                height = IntegerArgumentType.getInteger(context, "height");
            } catch (Throwable e) {
                width = image.getWidth();
                height = image.getHeight();
            }


            int finalHeight = height;
            int finalWidth = width;
            source.sendFeedback(Text.literal("Converting into maps...").formatted(Formatting.YELLOW), false);

            CompletableFuture.supplyAsync(() -> MapRenderer.render(image, mode, finalWidth, finalHeight)).thenAcceptAsync(mapImage -> {
                var items = MapRenderer.toVanillaItems(mapImage, source.getWorld(), input);
                boolean success = giveToPlayer(player, items, input, finalWidth, finalHeight);
                if (success) source.sendFeedback(Text.literal("Done!").formatted(Formatting.GREEN), false);
            }, source.getServer());
            return null;
        }, source.getServer());

        return 1;
    }

    public static boolean giveToPlayer(PlayerEntity player, List<ItemStack> items, String input, int width, int height) {
        int mapCount = player.getInventory().count(Items.MAP);

        if (CONFIG.checkQty && mapCount < items.size() && (!CONFIG.opOverrideQtyCheck && !player.hasPermissionLevel(2)) && !player.isCreative()) {
            player.sendMessage(Text.literal("You don't have enough maps in your inventory! You need " + (items.size() - mapCount) + " more maps!").formatted(Formatting.RED), false);
            return false;
        }

        if (items.size() == 1) {
            player.giveItemStack(items.get(0));
        } else if (CONFIG.useBundle) {
            var bundle = new ItemStack(Items.BUNDLE);
            var list = new NbtList();

            for (var item : items) {
                list.add(item.writeNbt(new NbtCompound()));
            }
            bundle.getOrCreateNbt().put("Items", list);
            bundle.getOrCreateNbt().putBoolean("image2map:quick_place", true);
            bundle.getOrCreateNbt().putInt("image2map:width", MathHelper.ceil(width / 128d));
            bundle.getOrCreateNbt().putInt("image2map:height", MathHelper.ceil(height / 128d));

            var lore = new NbtList();
            lore.add(NbtString.of(Text.Serializer.toJson(Text.literal(input))));
            bundle.getOrCreateSubNbt("display").put("Lore", lore);
            bundle.setCustomName(Text.literal("Maps").formatted(Formatting.GOLD));

            player.giveItemStack(bundle);
        } else {
            for (int i = 0; i < items.size(); i++) {
                ItemStack item = items.get(i);

                int x = i % (width / 128);
                int y = i / (width / 128);

                var lore = new NbtList();
                lore.add(NbtString.of(Text.Serializer.toJson(Text.literal( " (" + x + ", " + y + ")").formatted(Formatting.GRAY))));
                item.getOrCreateSubNbt("display").put("Lore", lore);
                player.getInventory().offerOrDrop(item);
            }
        }

        player.getInventory().remove(
                (stack) -> stack.getItem().equals(Items.MAP),
                items.size(),
                player.playerScreenHandler.getCraftingInput()
        );
        return true;
    }

    public static boolean clickItemFrame(PlayerEntity player, Hand hand, ItemFrameEntity itemFrameEntity) {
        var stack = player.getStackInHand(hand);

        if (stack.hasNbt() && stack.isOf(Items.BUNDLE) && stack.getNbt().getBoolean("image2map:quick_place")) {
            var world = itemFrameEntity.world;
            var start = itemFrameEntity.getBlockPos();
            var width = stack.getNbt().getInt("image2map:width");
            var height = stack.getNbt().getInt("image2map:height");

            var frames = new ItemFrameEntity[width * height];

            var facing = itemFrameEntity.getHorizontalFacing();
            Direction right;
            Direction down;

            int rot;

            if (facing.getAxis() != Direction.Axis.Y) {
                right = facing.rotateYCounterclockwise();
                down = Direction.DOWN;
                rot = 0;
            } else {
                right = player.getHorizontalFacing().rotateYClockwise();
                if (facing.getDirection() == Direction.AxisDirection.POSITIVE) {
                    down = right.rotateYClockwise();
                    rot = player.getHorizontalFacing().getOpposite().getHorizontal();
                } else {
                    down = right.rotateYCounterclockwise();
                    rot = (right.getAxis() == Direction.Axis.Z ? player.getHorizontalFacing() : player.getHorizontalFacing().getOpposite()).getHorizontal();
                }
            }

            var mut = start.mutableCopy();

            for (var x = 0; x < width; x++) {
                for (var y = 0; y < height; y++) {
                    mut.set(start);
                    mut.move(right, x);
                    mut.move(down, y);
                    var entities = world.getEntitiesByClass(ItemFrameEntity.class, Box.from(Vec3d.of(mut)), (entity1) -> entity1.getHorizontalFacing() == facing && entity1.getBlockPos().equals(mut));
                    if (!entities.isEmpty()) {
                        frames[x + y * width] = entities.get(0);
                    }
                }
            }

            for (var nbt : stack.getNbt().getList("Items", NbtElement.COMPOUND_TYPE)) {
                var map = ItemStack.fromNbt((NbtCompound) nbt);

                if (map.hasNbt()) {
                    var x = map.getNbt().getInt("image2map:x");
                    var y = map.getNbt().getInt("image2map:y");

                    var frame = frames[x + y * width];

                    if (frame != null && frame.getHeldItemStack().isEmpty()) {
                        frame.setHeldItemStack(map);
                        frame.setRotation(rot);
                    }
                }
            }

            stack.decrement(1);
            return true;
        }

        return false;
    }


    private static boolean isValid(String url) {
        try {
            new URL(url).toURI();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
