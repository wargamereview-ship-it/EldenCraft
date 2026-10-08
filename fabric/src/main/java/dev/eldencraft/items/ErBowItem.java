package dev.eldencraft.items;

import com.google.gson.JsonObject;
import java.util.function.Predicate;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Elden Ring bows and greatbows. */
public class ErBowItem extends BowItem {
	private static final Predicate<ItemStack> ARROWS = ErRanged.ammo(81), GREATARROWS = ErRanged.ammo(83);

	public ErBowItem(Properties properties) {
		super(properties);
	}

	@Override
	public Predicate<ItemStack> getAllSupportedProjectiles() {
		return stack -> ARROWS.test(stack) || GREATARROWS.test(stack);
	}

	@Override
	protected Projectile createProjectile(Level level, LivingEntity shooter, ItemStack weapon, ItemStack ammo, boolean crit) {
		JsonObject w = ErItems.weaponRow(weapon);
		boolean great = w != null && ErData.i(w, "t") == 53;
		if (great != GREATARROWS.test(ammo)) {
			// A greatbow needs greatarrows and a bow arrows; the wrong kind falls short.
			Projectile weak = super.createProjectile(level, shooter, weapon, ammo, false);
			if (weak instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow arrow) arrow.setBaseDamage(0.5);
			return weak;
		}
		Projectile projectile = super.createProjectile(level, shooter, weapon, ammo, crit);
		ErRanged.arm(projectile, shooter, weapon, ammo, 3.0);
		return projectile;
	}
}
