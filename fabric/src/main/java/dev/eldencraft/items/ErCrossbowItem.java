package dev.eldencraft.items;

import java.util.function.Predicate;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Elden Ring crossbows (bolts) and ballistas (greatbolts). */
public class ErCrossbowItem extends CrossbowItem {
	private static final Predicate<ItemStack> BOLTS = ErRanged.ammo(85, 86);

	public ErCrossbowItem(Properties properties) {
		super(properties);
	}

	@Override
	public Predicate<ItemStack> getAllSupportedProjectiles() {
		return BOLTS;
	}

	@Override
	public Predicate<ItemStack> getSupportedHeldProjectiles() {
		return BOLTS;
	}

	@Override
	protected Projectile createProjectile(Level level, LivingEntity shooter, ItemStack weapon, ItemStack ammo, boolean crit) {
		Projectile projectile = super.createProjectile(level, shooter, weapon, ammo, crit);
		ErRanged.arm(projectile, shooter, weapon, ammo, 3.15);
		return projectile;
	}
}
