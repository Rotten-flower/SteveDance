package dev.flower.stevedance;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * 原版地毯几何，演出时仅隐藏外观。
 * <p>Vanilla carpet geometry; playback hides only its appearance.</p>
 */
public final class StageMarkerBlock extends CarpetBlock {
    public static final BooleanProperty HIDDEN = BooleanProperty.create("hidden");

    public StageMarkerBlock() {
        super(BlockBehaviour.Properties.copy(Blocks.CYAN_CARPET));
        registerDefaultState(stateDefinition.any().setValue(HIDDEN, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(HIDDEN);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext c) {
        return super.getStateForPlacement(c);
    }

    @Override
    public RenderShape getRenderShape(BlockState s) {
        return s.getValue(HIDDEN) ? RenderShape.INVISIBLE : RenderShape.MODEL;
    }

    // 隐藏时不能继续裁掉下方地面的顶面。 / An invisible marker must not occlude the supporting block.
    @Override
    public net.minecraft.world.phys.shapes.VoxelShape getOcclusionShape(
            BlockState s, net.minecraft.world.level.BlockGetter level, BlockPos pos) {
        return s.getValue(HIDDEN)
                ? net.minecraft.world.phys.shapes.Shapes.empty()
                : super.getOcclusionShape(s, level, pos);
    }

    @Override
    public void setPlacedBy(
            Level level, BlockPos pos, BlockState state, LivingEntity entity, ItemStack stack) {
        if (!level.isClientSide && entity instanceof ServerPlayer p) {
            if (!WorldStagePlacement.bind(p, pos)) {
                level.destroyBlock(pos, false);
                if (!p.isCreative()) {
                    p.getInventory()
                            .placeItemBackInInventory(new ItemStack(WorldStage.MARKER_ITEM.get()));
                }
                return;
            }
            level.scheduleTick(pos, this, 2);
        }
    }

    @Override
    public void tick(
            BlockState state,
            ServerLevel level,
            BlockPos pos,
            net.minecraft.util.RandomSource random) {
        boolean hidden = PerformanceStageRegistry.performingAt(level, pos);
        if (hidden != state.getValue(HIDDEN)) {
            level.setBlock(pos, state.setValue(HIDDEN, hidden), 3);
        }
        level.scheduleTick(pos, this, 2);
    }

    @Override
    public void onPlace(BlockState s, Level level, BlockPos pos, BlockState old, boolean moving) {
        super.onPlace(s, level, pos, old, moving);
        if (!level.isClientSide) {
            level.scheduleTick(pos, this, 2);
        }
    }
}
