package dev.eldencraft.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.eldencraft.client.ShieldImpact;
import dev.eldencraft.client.SkyClient;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class FirstPersonHandsMixin {
	@Inject(method = "submitHandsWithItems", at = @At("HEAD"), cancellable = true)
	private void eldencraft$mode(float partialTick, PoseStack pose, SubmitNodeCollector collector,
		PlayerRenderState player, FirstPersonHandsAndItemsRenderState hands, CallbackInfo ci) {
		// Hearts/HUD are visible in ER mode too; first-person equipment belongs only to its camera.
		if (SkyClient.tookOver() && (!SkyClient.linked() || !SkyClient.sky().minecraftHands())) ci.cancel();
	}

	@Inject(method = "submitArmWithItem", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V", shift = At.Shift.AFTER))
	private void eldencraft$shieldRecoil(PlayerRenderState player, FirstPersonHandsAndItemsRenderState hands,
		float partialTick, float pitch, InteractionHand hand, float swing, ItemStack item,
		float equipped, PoseStack pose, SubmitNodeCollector collector, int light, CallbackInfo ci) {
		if (!item.has(DataComponents.BLOCKS_ATTACKS) || player.avatarRenderState == null) return;
		float recoil = ShieldImpact.recoil(hand);
		if (recoil <= 0) return;
		HumanoidArm arm = hand == InteractionHand.MAIN_HAND ? player.avatarRenderState.mainArm : player.avatarRenderState.mainArm.getOpposite();
		pose.translate(0, -0.035F * recoil, 0.18F * recoil);
		pose.rotateDegrees(Axis.XP, -14 * recoil);
		pose.rotateDegrees(Axis.ZP, (arm == HumanoidArm.LEFT ? 1 : -1) * 6 * recoil);
	}
}
