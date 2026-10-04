package dev.eldencraft.combat;

import dev.eldencraft.link.SkyLink;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;

/** Actual MC arrow retained at its impact, using actor-local coordinates rather than a world pin. */
public interface AttachedArrow {
	int eldencraft$attachedActor();
	void eldencraft$follow(double x, double y, double z, float yaw);

	static boolean follow(AbstractArrow arrow, SkyLink.Actor actor) {
		AttachedArrow attached = (AttachedArrow) arrow;
		if (attached.eldencraft$attachedActor() == 0 || actor == null || actor.dead()) return false;
		attached.eldencraft$follow(actor.x(), actor.y(), actor.z(), actor.yaw());
  // The native actor table already represents this frame: do not interpolate from an old body.
		arrow.xo = arrow.getX(); arrow.yo = arrow.getY(); arrow.zo = arrow.getZ();
		arrow.yRotO = arrow.getYRot(); arrow.xRotO = arrow.getXRot();
		return true;
	}
}
