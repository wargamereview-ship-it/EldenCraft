package dev.eldencraft.combat;

import com.mojang.serialization.Codec;
import dev.eldencraft.EldenCraft;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;

/**
 * A written field guide: the controls, the shops, what dying costs and how the armour sets work. A character gets one
 * the first time they join their world, and {@code /guide} gives another.
 */
public final class FieldGuide {
	private static final AttachmentType<Boolean> GIVEN = AttachmentRegistry.create(
		Identifier.fromNamespaceAndPath(EldenCraft.MOD_ID, "field_guide_given"), builder -> builder
			.initializer(() -> false).persistent(Codec.BOOL).copyOnDeath());

	private FieldGuide() {
	}

	public static void init() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer player = handler.getPlayer();
			if (!Boolean.TRUE.equals(player.getAttached(GIVEN))) {
				give(player);
				player.setAttached(GIVEN, true);
				player.sendSystemMessage(Component.literal("A field guide is in your inventory. Type /guide for another."));
			}
		});
		CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> dispatcher.register(
			Commands.literal("guide").executes(context -> {
				ServerPlayer player = context.getSource().getPlayer();
				if (player == null) {
					return 0;
				}
				give(player);
				return 1;
			})));
	}

	static ItemStack book() {
		List<Filterable<Component>> pages = new ArrayList<>();
		for (String page : GuideText.PAGES) {
			pages.add(Filterable.passThrough(Component.literal(page)));
		}
		ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
		book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough("EldenCraft Field Guide"), "EldenCraft", 0, pages, true));
		return book;
	}

	private static void give(ServerPlayer player) {
		ItemStack book = book();
		if (!player.getInventory().add(book)) {
			player.level().addFreshEntity(new ItemEntity(player.level(), player.getX(), player.getY(), player.getZ(), book));
		}
	}
}
