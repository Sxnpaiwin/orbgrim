package ac.grim.grimac.checks.impl.aim.iv;

import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import ac.grim.grimac.utils.data.packetentity.TypedPacketEntity;
import ac.grim.grimac.utils.math.Vector3dm;
import ac.grim.grimac.utils.nmsutil.ReachUtils;
import com.github.retrooper.packetevents.util.Vector3d;

/**
 * Shared server-side "perfect aim" solver for the Intave-ported combat checks.
 *
 * <p>Intave's accuracy/rotation heuristics all compare the player's rotations against a
 * server-computed ideal angle to the victim ({@code perfectYaw}, {@code closestYaw}).
 * Grim has no such feed, so this helper derives it from Grim's compensated entity
 * tracking: ideal yaw/pitch to the victim's hitbox center ({@code perfect*}) and to the
 * closest point on the hitbox ({@code closest*}), minimum eye-to-box reach, and whether
 * the player's current look ray actually intercepts the box ({@code inSight}).</p>
 *
 * <p>Angle convention is the inverse of {@link ReachUtils#getLook}: yaw {@code 0} faces
 * +Z (south), {@code -90} faces +X (east), pitch {@code -90} is straight up.</p>
 */
public final class PerfectAim {

    public final boolean valid;
    public final boolean living;
    public final int entityId;
    public final float perfectYaw;
    public final float perfectPitch;
    public final float closestYaw;
    public final float closestPitch;
    public final double reach;
    public final boolean inSight;
    public final double centerX;
    public final double centerY;
    public final double centerZ;

    private PerfectAim() {
        this.valid = false;
        this.living = false;
        this.entityId = -1;
        this.perfectYaw = 0;
        this.perfectPitch = 0;
        this.closestYaw = 0;
        this.closestPitch = 0;
        this.reach = Double.MAX_VALUE;
        this.inSight = false;
        this.centerX = 0;
        this.centerY = 0;
        this.centerZ = 0;
    }

    private PerfectAim(GrimPlayer player, int entityId, PacketEntity entity, SimpleCollisionBox box,
                       double eyeX, double eyeY, double eyeZ, float yaw, float pitch) {
        this.entityId = entityId;
        this.living = notTypedOrLiving(entity);

        this.centerX = (box.minX + box.maxX) * 0.5;
        this.centerY = (box.minY + box.maxY) * 0.5;
        this.centerZ = (box.minZ + box.maxZ) * 0.5;

        float[] toCenter = yawPitchTo(eyeX, eyeY, eyeZ, centerX, centerY, centerZ);
        this.perfectYaw = toCenter[0];
        this.perfectPitch = toCenter[1];

        double cx = clamp(eyeX, box.minX, box.maxX);
        double cy = clamp(eyeY, box.minY, box.maxY);
        double cz = clamp(eyeZ, box.minZ, box.maxZ);
        double dx = cx - eyeX, dy = cy - eyeY, dz = cz - eyeZ;
        if (dx * dx + dy * dy + dz * dz < 1e-9) {
            this.closestYaw = perfectYaw;
            this.closestPitch = perfectPitch;
        } else {
            float[] toClosest = yawPitchTo(eyeX, eyeY, eyeZ, cx, cy, cz);
            this.closestYaw = toClosest[0];
            this.closestPitch = toClosest[1];
        }

        this.reach = ReachUtils.getMinReachToBox(player, box);

        Vector3dm look = ReachUtils.getLook(player, yaw, pitch);
        look.multiply(reach + 3);
        Vector3d eyePos = new Vector3d(eyeX, eyeY, eyeZ);
        Vector3d end = eyePos.add(look.getX(), look.getY(), look.getZ());
        this.inSight = ReachUtils.isVecInside(box, eyePos)
                || ReachUtils.calculateIntercept(box, eyePos, end).first() != null;
        this.valid = true;
    }

    public static PerfectAim compute(GrimPlayer player, int entityId, float yaw, float pitch) {
        return computeAt(player, entityId, player.x, player.y + player.getEyeHeight(), player.z, yaw, pitch);
    }

    /** Historical-tick variant: eye/look state supplied explicitly (Intave snap confirm). */
    public static PerfectAim computeAt(GrimPlayer player, int entityId,
                                       double eyeX, double eyeY, double eyeZ,
                                       float yaw, float pitch) {
        return computeAtExpanded(player, entityId, eyeX, eyeY, eyeZ, yaw, pitch, 0);
    }

    /** Variant with a hitbox expansion margin (Intave cursor-upon-entity checks). */
    public static PerfectAim computeExpanded(GrimPlayer player, int entityId,
                                             float yaw, float pitch, double expand) {
        return computeAtExpanded(player, entityId, player.x, player.y + player.getEyeHeight(),
                player.z, yaw, pitch, expand);
    }

    private static PerfectAim computeAtExpanded(GrimPlayer player, int entityId,
                                                double eyeX, double eyeY, double eyeZ,
                                                float yaw, float pitch, double expand) {
        if (player.disableGrim) return new PerfectAim();
        PacketEntity entity;
        try {
            entity = player.compensatedEntities.entityMap.get(entityId);
        } catch (Exception e) {
            return new PerfectAim();
        }
        if (entity == null || entity.isDead) return new PerfectAim();
        SimpleCollisionBox box;
        try {
            box = entity.getPossibleCollisionBoxes();
        } catch (Exception e) {
            return new PerfectAim();
        }
        if (box == null) return new PerfectAim();
        if (expand > 0) box.expand(expand);
        return new PerfectAim(player, entityId, entity, box, eyeX, eyeY, eyeZ, yaw, pitch);
    }

    private static boolean notTypedOrLiving(PacketEntity entity) {
        if (entity instanceof TypedPacketEntity) {
            return ((TypedPacketEntity) entity).isLivingEntity;
        }
        return true;
    }

    /** Inverse of the modern-client branch of {@link ReachUtils#getLook}. */
    static float[] yawPitchTo(double eyeX, double eyeY, double eyeZ,
                              double tx, double ty, double tz) {
        double dx = tx - eyeX;
        double dy = ty - eyeY;
        double dz = tz - eyeZ;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-9) return new float[]{0f, 0f};
        float yaw = (float) -Math.toDegrees(Math.atan2(dx, dz));
        float pitch = (float) Math.toDegrees(Math.asin(clamp(-dy / len, -1.0, 1.0)));
        return new float[]{yaw, pitch};
    }

    /** Smallest signed angle from {@code from} to {@code to}, in degrees (-180, 180]. */
    public static float angleDistance(float from, float to) {
        float diff = (to - from) % 360f;
        if (diff > 180f) diff -= 360f;
        if (diff <= -180f) diff += 360f;
        return diff;
    }

    public static double distanceTo(float yaw, float pitch, float perfectYaw, float perfectPitch) {
        double dyaw = angleDistance(yaw, perfectYaw);
        double dpitch = pitch - perfectPitch;
        return Math.sqrt(dyaw * dyaw + dpitch * dpitch);
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
