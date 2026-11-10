package dev.flower.stevedance;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.UUID;

/**
 * 实体渲染器使用四边形；每个三角形重复最后一个顶点。
 * <p>The server owns a real, persistent stage anchor in the current level.</p>
 */
public final class WorldStage {
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, "stevedance");
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, "stevedance");
    public static final DeferredRegister<BlockEntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, "stevedance");
    public static final RegistryObject<dev.flower.stevedance.StageMarkerBlock> MARKER =
            BLOCKS.register("stage_marker", dev.flower.stevedance.StageMarkerBlock::new);
    public static final RegistryObject<Item> MARKER_ITEM =
            ITEMS.register(
                    "stage_marker", () -> new BlockItem(MARKER.get(), new Item.Properties()));
    public static final RegistryObject<AnchorBlock> ANCHOR =
            BLOCKS.register("arcade", AnchorBlock::new);
    public static final RegistryObject<Item> ITEM =
            ITEMS.register(
                    "arcade",
                    () ->
                            new BlockItem(ANCHOR.get(), new Item.Properties()) {
                                // 合成定位地毯时返还原机器及其数据。 / Preserve the arcade and its NBT in crafting.
                                @Override
                                public boolean hasCraftingRemainingItem(ItemStack stack) {
                                    return true;
                                }

                                @Override
                                public ItemStack getCraftingRemainingItem(ItemStack stack) {
                                    ItemStack remaining = stack.copy();
                                    remaining.setCount(1);
                                    return remaining;
                                }

                                @Override
                                public void initializeClient(
                                        java.util.function.Consumer<
                                                        net.minecraftforge.client.extensions.common
                                                                .IClientItemExtensions>
                                                consumer) {
                                    consumer.accept(
                                            new net.minecraftforge.client.extensions.common
                                                    .IClientItemExtensions() {
                                                private net.minecraft.client.renderer
                                                                .BlockEntityWithoutLevelRenderer
                                                        renderer;

                                                @Override
                                                public net.minecraft.client.renderer
                                                                .BlockEntityWithoutLevelRenderer
                                                        getCustomRenderer() {
                                                    if (renderer == null) {
                                                        renderer =
                                                                new dev.flower.stevedance.client
                                                                        .ArcadeMesh.ItemRenderer();
                                                    }
                                                    return renderer;
                                                }
                                            });
                                }
                            });
    public static final RegistryObject<Item> PHOTO_ITEM =
            ITEMS.register(
                    "camera",
                    () ->
                            new Item(new Item.Properties().stacksTo(1)) {
                                @Override
                                public net.minecraft.world.InteractionResultHolder<ItemStack> use(
                                        Level level, Player player, InteractionHand hand) {
                                    if (level.isClientSide) {
                                        net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                                                net.minecraftforge.api.distmarker.Dist.CLIENT,
                                                () ->
                                                        () ->
                                                                dev.flower.stevedance.client
                                                                        .PhotoMode.open());
                                    }
                                    return net.minecraft.world.InteractionResultHolder.sidedSuccess(
                                            player.getItemInHand(hand), level.isClientSide);
                                }
                            });
    public static final RegistryObject<BlockEntityType<Anchor>> TYPE =
            ENTITIES.register(
                    "arcade",
                    () -> BlockEntityType.Builder.of(Anchor::new, ANCHOR.get()).build(null));

    public static final class AnchorBlock extends BaseEntityBlock {
        private static final VoxelShape SHAPE = Block.box(-.7, 0, -.6, 16.7, 16, 14);

        public AnchorBlock() {
            super(BlockBehaviour.Properties.of().strength(2).noOcclusion().lightLevel(s -> 15));
            registerDefaultState(
                    stateDefinition
                            .any()
                            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH)
                            .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER)
                            .setValue(BlockStateProperties.LIT, false));
        }

        @Override
        protected void createBlockStateDefinition(
                StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(
                    BlockStateProperties.HORIZONTAL_FACING,
                    BlockStateProperties.DOUBLE_BLOCK_HALF,
                    BlockStateProperties.LIT);
        }

        @Override
        public BlockState getStateForPlacement(
                net.minecraft.world.item.context.BlockPlaceContext c) {
            return c.getClickedPos().getY() < c.getLevel().getMaxBuildHeight() - 1
                            && c.getLevel()
                                    .getBlockState(c.getClickedPos().above())
                                    .canBeReplaced(c)
                    ? defaultBlockState()
                            .setValue(
                                    BlockStateProperties.HORIZONTAL_FACING,
                                    c.getHorizontalDirection().getOpposite())
                    : null;
        }

        @Override
        public VoxelShape getShape(
                BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            return SHAPE;
        }

        @Override
        public RenderShape getRenderShape(BlockState state) {
            return RenderShape.ENTITYBLOCK_ANIMATED;
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER
                    ? new Anchor(pos, state)
                    : null;
        }

        @Override
        public InteractionResult use(
                BlockState state,
                Level level,
                BlockPos pos,
                Player player,
                InteractionHand hand,
                BlockHitResult hit) {
            if (!level.isClientSide && player instanceof ServerPlayer p) {
                dev.flower.stevedance.ArcadeSessions.open(
                        p,
                        state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)
                                        == DoubleBlockHalf.UPPER
                                ? pos.below()
                                : pos);
                RhythmSessionService.open(p);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        @Override
        public void setPlacedBy(
                Level level,
                BlockPos pos,
                BlockState state,
                net.minecraft.world.entity.LivingEntity placer,
                ItemStack stack) {
            if (!level.isClientSide) {
                level.setBlock(
                        pos.above(),
                        state.setValue(
                                BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER),
                        3);
                if (level.getBlockEntity(pos) instanceof Anchor a && placer instanceof Player p) {
                    a.owner = p.getUUID();
                    a.sync();
                }
            }
        }

        @Override
        public BlockState updateShape(
                BlockState state,
                Direction side,
                BlockState neighbor,
                net.minecraft.world.level.LevelAccessor level,
                BlockPos pos,
                BlockPos neighborPos) {
            boolean lower =
                    state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER;
            if (side == (lower ? Direction.UP : Direction.DOWN)
                    && (!neighbor.is(this)
                            || neighbor.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)
                                    == state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF))) {
                return Blocks.AIR.defaultBlockState();
            }
            return super.updateShape(state, side, neighbor, level, pos, neighborPos);
        }

        @Override
        public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
            if (!level.isClientSide
                    && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)
                            == DoubleBlockHalf.UPPER) {
                BlockPos lower = pos.below();
                var other = level.getBlockState(lower);
                if (other.is(this)) {
                    if (!player.isCreative()) {
                        Block.dropResources(
                                other,
                                level,
                                lower,
                                level.getBlockEntity(lower),
                                player,
                                player.getMainHandItem());
                    }
                    level.setBlock(lower, Blocks.AIR.defaultBlockState(), 35);
                }
            }
            super.playerWillDestroy(level, pos, state, player);
        }

        @Override
        public BlockState rotate(BlockState state, Rotation r) {
            return state.setValue(
                    BlockStateProperties.HORIZONTAL_FACING,
                    r.rotate(state.getValue(BlockStateProperties.HORIZONTAL_FACING)));
        }

        @Override
        public BlockState mirror(BlockState state, Mirror m) {
            return state.rotate(
                    m.getRotation(state.getValue(BlockStateProperties.HORIZONTAL_FACING)));
        }
    }

    public static final class Anchor extends BlockEntity {
        private String song = "two_breaths_f2nd";
        private int schema = 1;
        private UUID owner;
        private BlockPos marker;

        public BlockPos marker() {
            return marker;
        }

        public void marker(BlockPos pos) {
            marker = pos == null ? null : pos.immutable();
            sync();
        }

        public Anchor(BlockPos pos, BlockState state) {
            super(TYPE.get(), pos, state);
        }

        @Override
        public void onLoad() {
            super.onLoad();
            if (level != null
                    && !level.isClientSide
                    && getBlockState().getValue(BlockStateProperties.LIT)) {
                var off = getBlockState().setValue(BlockStateProperties.LIT, false);
                level.setBlock(worldPosition, off, 3);
                var upper = level.getBlockState(worldPosition.above());
                if (upper.is(ANCHOR.get())) {
                    level.setBlock(
                            worldPosition.above(),
                            upper.setValue(BlockStateProperties.LIT, false),
                            3);
                }
            }
        }

        public String song() {
            return song;
        }

        public float yaw() {
            return getBlockState().getValue(BlockStateProperties.HORIZONTAL_FACING).toYRot();
        }

        public Vec3 origin() {
            return new Vec3(
                    worldPosition.getX() + .5,
                    worldPosition.getY() + .125,
                    worldPosition.getZ() + .5);
        }

        public boolean availableTo(ServerPlayer p) {
            return schema == 1
                    && (owner == null || owner.equals(p.getUUID()) || p.hasPermissions(2));
        }

        public void configureSession(String song, UUID owner) {
            this.song = song;
            this.owner = owner;
        }

        public void configure(String song, UUID owner) {
            this.song = song;
            this.owner = owner;
            sync();
        }

        private void sync() {
            setChanged();
            if (level != null) {
                level.sendBlockUpdated(
                        worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
            }
        }

        @Override
        protected void saveAdditional(CompoundTag tag) {
            super.saveAdditional(tag);
            if (marker != null) {
                tag.putLong("marker", marker.asLong());
            }
            tag.putInt("schema", schema);
            tag.putString("song", song);
            if (owner != null) {
                tag.putUUID("owner", owner);
            }
        }

        @Override
        public void load(CompoundTag tag) {
            super.load(tag);
            marker = tag.contains("marker") ? BlockPos.of(tag.getLong("marker")) : null;
            schema = tag.contains("schema") ? tag.getInt("schema") : 1;
            song = tag.contains("song") ? tag.getString("song") : "two_breaths_f2nd";
            owner = tag.hasUUID("owner") ? tag.getUUID("owner") : null;
            if (schema != 1) {
                SteveDanceMod.LOGGER.warn(
                        "Unsupported stage schema {} at {}; keeping its saved data",
                        schema,
                        worldPosition);
            }
        }

        @Override
        public CompoundTag getUpdateTag() {
            return saveWithoutMetadata();
        }

        @Override
        public ClientboundBlockEntityDataPacket getUpdatePacket() {
            return ClientboundBlockEntityDataPacket.create(this);
        }

        @Override
        public AABB getRenderBoundingBox() {
            return new AABB(worldPosition).inflate(26, 9, 26);
        }
    }

    /**
     * 隐藏的 YSM 控制器可能仍在运行，而同一角色渲染为女仆。
     * <p>A bounded local lookup, only performed when the player requests an action.</p>
     */
    public static Anchor nearby(ServerPlayer p) {
        Anchor best = null;
        double distance = 36;
        BlockPos feet = p.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-3, -2, -3), feet.offset(3, 1, 3))) {
            if (!p.serverLevel().hasChunkAt(pos)) {
                continue;
            }
            if (p.serverLevel().getBlockEntity(pos) instanceof Anchor a) {
                double d = p.position().distanceToSqr(a.origin());
                if (d < distance) {
                    best = a;
                    distance = d;
                }
            }
        }
        return best;
    }
}
