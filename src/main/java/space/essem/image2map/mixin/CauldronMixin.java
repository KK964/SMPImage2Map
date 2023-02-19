package space.essem.image2map.mixin;

import net.minecraft.block.*;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import space.essem.image2map.Image2Map;

@Mixin(AbstractCauldronBlock.class)
public class CauldronMixin {
    @Inject(at = @At("HEAD"), method = "onUse", cancellable = true)
    private void onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit, CallbackInfoReturnable<ActionResult> cir) {
        if (world.isClient) return;
        if (!Image2Map.CONFIG.resetableMaps) return;
        ItemStack stack = player.getStackInHand(hand);
        if (!stack.getItem().equals(Items.FILLED_MAP)) return;
        if (!state.getBlock().equals(Blocks.WATER_CAULDRON)) return;
        LeveledCauldronBlock block = (LeveledCauldronBlock) state.getBlock();
        if (!block.equals(Blocks.WATER_CAULDRON)) return;
        if (!block.isFull(state)) return;
        stack.decrement(1);
        player.getInventory().offerOrDrop(new ItemStack(Items.MAP));
        cir.setReturnValue(ActionResult.SUCCESS);
    }
}
