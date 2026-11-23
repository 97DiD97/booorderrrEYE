package dev.eyesky.border;

import dev.eyesky.Emotion;
import dev.eyesky.EyeManager;
import dev.eyesky.EyeSkyPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/** Глаз летит к границе, рисует руны и "кистью" расширяет барьер. */
final class BorderExpansion {
    private enum Phase { FLY, CHARGE, EXPAND, FINISH }

    private static final double MAX_SIZE = 59_999_968.0;
    private static final Color PURPLE = Color.fromRGB(176, 64, 255);
    private static final Color LILAC = Color.fromRGB(235, 200, 255);
    private static final Color CYAN = Color.fromRGB(90, 220, 255);
    private static final Color WHITE = Color.fromRGB(255, 255, 255);
    private static final int FINISH_TICKS = 45;

    private record Pt(double x, double y, double z, int born) { }
    private record Curtain(double x, double z, double top, int born) { }
    private record Ripple(double x, double y, double z, int born) { }

    private final EyeBorderPlugin plugin;
    private final EyeSkyPlugin sky;
    private final EyeManager eye;
    private final EyeBridge bridge;
    private final CommandSender sender;
    private final World world;
    private final WorldBorder border;
    private final Random rnd = new Random();

    private final int blocks;
    private final int drawTicks;
    private final int chargeTicks;
    private final double flySpeed;
    private final int flyTimeout;
    private final boolean shakeOn, shakeRotation, shakeTilt;
    private final double shakeStrength, shakeRadius;

    private final double cx, cz, half, startSize, targetSize;
    private final double nx, nz, tx, tz;   // нормаль стены (наружу) и касательная
    private final double uOff;             // где вдоль стены висит глаз
    private final double hoverY;

    private Phase phase = Phase.FLY;
    private int t;
    private int total;
    private BukkitTask task;
    private double d;                      // на сколько стена сдвинулась наружу
    private boolean ended;

    private final List<Pt> trail = new ArrayList<>();
    private final List<Curtain> curtains = new ArrayList<>();
    private final List<Ripple> ripples = new ArrayList<>();
    private Pt lastBrush;

    BorderExpansion(EyeBorderPlugin plugin, EyeSkyPlugin sky, CommandSender sender, int blocks, int seconds) {
        this.plugin = plugin;
        this.sky = sky;
        this.sender = sender;
        this.blocks = blocks;
        this.eye = sky.eye();
        if (eye == null) throw new IllegalStateException("Глаз не запущен.");
        if (eye.isHidden()) throw new IllegalStateException("Глаз сейчас скрыт. Сначала /eye appear");
        if (eye.laserBusy() || eye.entranceBusy()) {
            throw new IllegalStateException("Глаз занят (лазер или появление). Подожди или /eye stop");
        }
        try {
            this.bridge = new EyeBridge(eye);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Не удалось подключиться к глазу: " + e);
        }
        this.world = bridge.world();
        Vector p = bridge.position();
        if (world == null || p == null) {
            throw new IllegalStateException("Глаза сейчас нет в мире. Попробуй /eye summon");
        }
        this.border = world.getWorldBorder();

        var cfg = plugin.getConfig();
        this.drawTicks = seconds * 20;
        this.chargeTicks = Math.max(20, cfg.getInt("border.charge-seconds", 4) * 20);
        this.flySpeed = Math.max(0.5, cfg.getDouble("border.fly-speed", 3.0));
        this.flyTimeout = Math.max(5, cfg.getInt("border.fly-timeout-seconds", 45)) * 20;
        this.shakeOn = cfg.getBoolean("shake.enabled", true);
        this.shakeStrength = cfg.getDouble("shake.strength", 1.0);
        this.shakeRadius = cfg.getDouble("shake.radius", 0);
        this.shakeRotation = cfg.getBoolean("shake.rotation", true);
        this.shakeTilt = cfg.getBoolean("shake.tilt", true);

        this.cx = border.getCenter().getX();
        this.cz = border.getCenter().getZ();
        this.startSize = border.getSize();
        this.half = startSize / 2.0;
        double add = cfg.getBoolean("border.per-side", true) ? 2.0 * blocks : blocks;
        this.targetSize = Math.min(MAX_SIZE, startSize + add);

        // ближайшая стена
        double[] dist = {
                Math.abs(cx + half - p.getX()), Math.abs(p.getX() - (cx - half)),
                Math.abs(cz + half - p.getZ()), Math.abs(p.getZ() - (cz - half))
        };
        int best = 0;
        for (int i = 1; i < 4; i++) if (dist[i] < dist[best]) best = i;
        double[][] normals = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        this.nx = normals[best][0];
        this.nz = normals[best][1];
        this.tx = -nz;
        this.tz = nx;

        double along = (p.getX() - cx) * tx + (p.getZ() - cz) * tz;
        double lim = Math.max(0, half - 25);
        this.uOff = Math.max(-lim, Math.min(lim, along));
        this.hoverY = p.getY();
    }

    // ===== запуск / остановка =====

    void start() {
        msg("Глаз летит к границе мира...", NamedTextColor.LIGHT_PURPLE);
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    void abort(boolean notify) {
        if (ended) return;
        ended = true;
        if (task != null) task.cancel();
        bridge.hold(null);
        // оставляем границу там, где она сейчас
        if (phase == Phase.EXPAND) border.setSize(border.getSize());
        plugin.cleared(this);
        if (notify) msg("Расширение прервано.", NamedTextColor.YELLOW);
    }

    private void finishOk() {
        if (ended) return;
        ended = true;
        if (task != null) task.cancel();
        bridge.hold(null);
        plugin.cleared(this);
        msg("Готово! Граница теперь " + Math.round(targetSize) + " блоков в поперечнике.", NamedTextColor.GREEN);
    }

    private void msg(String s, NamedTextColor c) {
        sender.sendMessage(Component.text(s, c));
    }

    private void tick() {
        try {
            switch (phase) {
                case FLY -> tickFly();
                case CHARGE -> tickCharge();
                case EXPAND -> tickExpand();
                case FINISH -> tickFinish();
            }
            t++;
            total++;
        } catch (Throwable e) {
            plugin.getLogger().warning("Ошибка анимации границы: " + e);
            abort(true);
        }
    }

    private void next(Phase p) {
        phase = p;
        t = -1; // после t++ будет 0
    }

    // ===== геометрия =====

    private Vector wall(double out, double y, double du) {
        double u = uOff + du;
        return new Vector(cx + nx * (half + out) + tx * u, y, cz + nz * (half + out) + tz * u);
    }

    private Vector eyePos() {
        Vector p = bridge.position();
        return p != null ? p : wall(d, hoverY, 0);
    }

    /** Точка в плоскости стены вокруг центра c: вдоль стены cos*r, вверх sin*r. */
    private Vector plane(Vector c, double r, double ang) {
        double a = Math.cos(ang) * r;
        return new Vector(c.getX() + tx * a, c.getY() + Math.sin(ang) * r, c.getZ() + tz * a);
    }

    private double groundY(double x, double z, double fallback) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        if (!world.isChunkLoaded(bx >> 4, bz >> 4)) return fallback;
        return world.getHighestBlockYAt(bx, bz) + 1;
    }

    // ===== фазы =====

    private void tickFly() {
        Vector h = wall(0, hoverY, 0);
        bridge.hold(h);
        bridge.holdSpeed(flySpeed);
        Vector p = eyePos();
        if (t % 40 == 0 && t > 0) {
            actionBarAll(Component.text("Глаз что-то задумал...", NamedTextColor.DARK_PURPLE));
        }
        if (p.distance(h) < 3.0) {
            next(Phase.CHARGE);
            return;
        }
        if (t > flyTimeout) {
            bridge.teleport(h);
            next(Phase.CHARGE);
        }
    }

    private void tickCharge() {
        Vector h = wall(0, hoverY, 0);
        bridge.hold(h);
        bridge.holdSpeed(flySpeed);
        Vector e = eyePos();
        double q = Math.min(1.0, (double) t / chargeTicks);

        if (t % 20 == 0) eye.forceEmotion(Emotion.ALERT, 2);
        if (t == 0) {
            world.playSound(toLoc(e), Sound.ENTITY_WARDEN_SONIC_CHARGE, SoundCategory.MASTER, 6f, 0.6f);
            broadcastSound(Sound.ENTITY_ENDER_DRAGON_GROWL, 0.7f, 0.5f);
            for (Player pl : world.getPlayers()) {
                pl.showTitle(Title.title(
                        Component.text("👁", NamedTextColor.DARK_PURPLE),
                        Component.text("Глаз рисует границу...", NamedTextColor.LIGHT_PURPLE),
                        Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2200), Duration.ofMillis(600))));
            }
        }

        // руническое кольцо рисуется как дуга
        double R = 9.0;
        double arcP = Math.min(1.0, q / 0.55);
        int pts = 110;
        int n = (int) (pts * arcP);
        for (int i = 0; i < n; i++) {
            double ang = -Math.PI / 2 + 2 * Math.PI * i / pts;
            Vector v = plane(e, R, ang);
            dust(v, PURPLE, LILAC, 1.5f);
            if (i % 9 == 0) dust(plane(e, R + 1.3, ang), CYAN, PURPLE, 1.0f);
        }
        if (arcP < 1.0) {
            double ang = -Math.PI / 2 + 2 * Math.PI * arcP;
            Vector head = plane(e, R, ang);
            burst(Particle.END_ROD, head, 4, 0.2, 0.2, 0.2, 0.04);
            burst(Particle.ELECTRIC_SPARK, head, 3, 0.3, 0.3, 0.3, 0.1);
        }

        // пентаграмма (вторая часть зарядки)
        double starP = Math.max(0, (q - 0.45) / 0.5);
        if (starP > 0) {
            double sp = Math.min(1.0, starP) * 5.0;
            for (int k = 0; k < 5; k++) {
                if (sp <= k) break;
                double part = Math.min(1.0, sp - k);
                double a1 = -Math.PI / 2 + 2 * Math.PI * k / 5.0;
                double a2 = -Math.PI / 2 + 2 * Math.PI * ((k + 2) % 5) / 5.0;
                Vector p1 = plane(e, R * 0.92, a1), p2 = plane(e, R * 0.92, a2);
                int steps = 14;
                for (int s = 0; s <= steps * part; s++) {
                    double f = (double) s / steps;
                    dust(lerp(p1, p2, f), LILAC, CYAN, 1.2f);
                }
            }
        }

        // притягиваются искры
        for (int i = 0; i < 6; i++) {
            Vector dir = randomUnit();
            Vector from = e.clone().add(dir.clone().multiply(R * 2.4));
            spark(Particle.END_ROD, from, 0, -dir.getX(), -dir.getY(), -dir.getZ(), 0.45);
        }
        // предупреждающая линия будущей стены
        if (t % 4 == 0) {
            double wallOut = (targetSize - startSize) / 2.0;
            for (int i = -30; i <= 30; i += 3) {
                Vector w = wall(wallOut, hoverY - 20 + (t % 16), i);
                dust(w, CYAN, PURPLE, 1.0f);
            }
        }

        if (t % 10 == 0) {
            world.playSound(toLoc(e), Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.MASTER, 5f, (float) (0.5 + q * 1.5));
        }
        if (t % 20 == 0) {
            actionBarAll(Component.text("Глаз заряжается...  " + (int) (q * 100) + "%", NamedTextColor.LIGHT_PURPLE));
        }
        shake((0.12 + 0.55 * q) * 0.8, e);

        if (t >= chargeTicks) {
            world.playSound(toLoc(e), Sound.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.MASTER, 8f, 0.7f);
            burst(Particle.EXPLOSION_EMITTER, e, 1, 0, 0, 0, 0);
            eye.forceEmotion(Emotion.SURPRISED, 2);
            next(Phase.EXPAND);
        }
    }

    private void tickExpand() {
        double p = Math.min(1.0, (double) t / drawTicks);
        double e = p * p * (3 - 2 * p);
        double size = startSize + (targetSize - startSize) * e;
        border.setSize(size);
        d = (size - startSize) / 2.0;

        // глаз едет вместе со стеной
        Vector h = wall(d, hoverY, 0);
        double rate = (targetSize - startSize) / 2.0 / drawTicks;
        bridge.hold(h);
        bridge.holdSpeed(Math.max(flySpeed, rate * 2.5 + 1.0));
        Vector eyeP = eyePos();
        if (eyeP.distance(h) > 25) {
            bridge.teleport(h);
            eyeP = h.clone();
        }
        if (t % 20 == 0) eye.forceEmotion(Emotion.ANGRY, 2);

        // кисть
        double amp = Math.min(50.0, Math.max(6.0, half * 0.9));
        double du = amp * Math.sin(t * 0.10) + 8 * Math.sin(t * 0.53);
        double vy = hoverY - 18 + 16 * Math.sin(t * 0.23 + 1.3) + 5 * Math.sin(t * 0.9);
        Vector b = wall(d, vy, du);
        Pt cur = new Pt(b.getX(), b.getY(), b.getZ(), t);
        if (lastBrush != null) {
            for (int s = 1; s <= 4; s++) {
                double f = s / 4.0;
                trail.add(new Pt(lastBrush.x + (cur.x - lastBrush.x) * f, lastBrush.y + (cur.y - lastBrush.y) * f,
                        lastBrush.z + (cur.z - lastBrush.z) * f, t));
            }
        } else {
            trail.add(cur);
        }
        lastBrush = cur;

        if (t % 2 == 0) curtains.add(new Curtain(b.getX(), b.getZ(), b.getY(), t));
        if (t % 8 == 0) ripples.add(new Ripple(b.getX(), b.getY(), b.getZ(), t));

        // шлейф кисти
        for (Iterator<Pt> it = trail.iterator(); it.hasNext(); ) {
            Pt pt = it.next();
            int age = t - pt.born;
            if (age > 30) { it.remove(); continue; }
            float k = 1f - age / 30f;
            dust(pt.x, pt.y, pt.z, WHITE, PURPLE, 0.7f + 1.6f * k);
        }
        // занавес-капли вниз до земли
        for (Iterator<Curtain> it = curtains.iterator(); it.hasNext(); ) {
            Curtain c = it.next();
            int age = t - c.born;
            if (age > 18) { it.remove(); continue; }
            double g = groundY(c.x, c.z, c.top - 60);
            float k = 1f - age / 18f;
            for (double y = c.top; y > g; y -= 3.0) {
                dust(c.x, y, c.z, CYAN, PURPLE, 0.6f + 0.9f * k);
            }
        }
        // кольца-рябь
        for (Iterator<Ripple> it = ripples.iterator(); it.hasNext(); ) {
            Ripple r = it.next();
            int age = t - r.born;
            if (age > 14) { it.remove(); continue; }
            Vector c = new Vector(r.x, r.y, r.z);
            double rad = 1.0 + age * 0.9;
            for (int i = 0; i < 28; i++) {
                dust(plane(c, rad, 2 * Math.PI * i / 28), LILAC, PURPLE, 1.1f);
            }
        }
        // луч от глаза к кисти
        Vector from = eyeP;
        for (int i = 0; i <= 16; i++) {
            Vector v = lerp(from, b, i / 16.0);
            dust(v, PURPLE, CYAN, 1.3f);
            if (i % 4 == 0) burst(Particle.END_ROD, v, 1, 0.1, 0.1, 0.1, 0.01);
        }
        burst(Particle.ELECTRIC_SPARK, b, 4, 0.5, 0.5, 0.5, 0.2);
        // граница-кромка: пунктир по линии стены
        if (t % 3 == 0) {
            for (int i = -60; i <= 60; i += 4) {
                Vector w = wall(d, hoverY - 30 + 40 * Math.abs(Math.sin(t * 0.07 + i * 0.1)), i);
                dust(w, CYAN, WHITE, 1.0f);
            }
        }

        // звук
        if (t % 12 == 0) {
            world.playSound(toLoc(b), Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.MASTER, 5f, (float) (0.5 + e * 1.5));
        }
        if (t % 25 == 0) broadcastSound(Sound.ENTITY_WARDEN_HEARTBEAT, 1.0f, 0.6f);

        if (t % 5 == 0) {
            int filled = (int) Math.round(p * 20);
            String bar = "█".repeat(filled) + "░".repeat(20 - filled);
            actionBarAll(Component.text("Граница: ", NamedTextColor.DARK_PURPLE)
                    .append(Component.text("[" + bar + "] ", NamedTextColor.LIGHT_PURPLE))
                    .append(Component.text("+" + Math.round(d) + " / " + blocks, NamedTextColor.WHITE)));
        }

        shake(1.0 * (0.45 + 0.55 * Math.sin(Math.PI * p)), b);

        if (t >= drawTicks) {
            border.setSize(targetSize);
            d = (targetSize - startSize) / 2.0;
            next(Phase.FINISH);
        }
    }

    private void tickFinish() {
        Vector e = eyePos();
        bridge.hold(wall(d, hoverY, 0));
        if (t == 0) {
            eye.forceEmotion(Emotion.HAPPY, 5);
            broadcastSound(Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.0f, 0.7f);
            world.playSound(toLoc(e), Sound.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.MASTER, 10f, 0.5f);
            burst(Particle.EXPLOSION_EMITTER, e, 2, 1, 1, 1, 0);
            for (Player pl : world.getPlayers()) {
                pl.showTitle(Title.title(
                        Component.text("Граница расширена", NamedTextColor.LIGHT_PURPLE),
                        Component.text("+" + blocks + (blocks == 1 ? " блок" : " блоков"), NamedTextColor.WHITE),
                        Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2500), Duration.ofMillis(800))));
            }
        }
        // расходящаяся волна по стене
        double rad = 2.0 + t * 2.4;
        int pts = (int) Math.min(160, 40 + rad * 2);
        for (int i = 0; i < pts; i++) {
            double ang = 2 * Math.PI * i / pts;
            dust(plane(e, rad, ang), WHITE, PURPLE, 1.8f);
            if (i % 3 == 0) dust(plane(e, rad * 0.8, ang), CYAN, PURPLE, 1.2f);
        }
        burst(Particle.END_ROD, e, 12, 4, 4, 4, 0.15);

        shake(1.3 * (1.0 - (double) t / FINISH_TICKS), e);

        if (t >= FINISH_TICKS) finishOk();
    }

    // ===== тряска экрана =====

    private void shake(double amp, Vector focus) {
        if (!shakeOn || amp <= 0.02) return;
        for (Player pl : world.getPlayers()) {
            double dx = pl.getLocation().getX() - focus.getX();
            double dz = pl.getLocation().getZ() - focus.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            double s;
            if (shakeRadius > 0 && dist > shakeRadius) continue;
            double near = 80.0;
            double far = shakeRadius > 0 ? shakeRadius : 400.0;
            if (dist <= near) s = 1.0;
            else s = 1.0 - 0.65 * Math.min(1.0, (dist - near) / Math.max(1.0, far - near));
            double a = amp * s * shakeStrength;
            if (a < 0.05) continue;
            if (shakeRotation) {
                float yaw = pl.getLocation().getYaw() + (float) ((rnd.nextDouble() - 0.5) * 2.0 * a * 1.6);
                float pitch = pl.getLocation().getPitch() + (float) ((rnd.nextDouble() - 0.5) * 2.0 * a * 1.1);
                pitch = Math.max(-89f, Math.min(89f, pitch));
                pl.setRotation(yaw, pitch);
            }
            if (shakeTilt && a > 0.2 && total % 4 == 0) {
                pl.playHurtAnimation(rnd.nextBoolean() ? 90f : -90f);
            }
        }
    }

    // ===== утилиты =====

    private void actionBarAll(Component c) {
        for (Player pl : world.getPlayers()) pl.sendActionBar(c);
    }

    private void broadcastSound(Sound s, float vol, float pitch) {
        for (Player pl : world.getPlayers()) pl.playSound(pl.getLocation(), s, SoundCategory.MASTER, vol, pitch);
    }

    private org.bukkit.Location toLoc(Vector v) {
        return new org.bukkit.Location(world, v.getX(), v.getY(), v.getZ());
    }

    private void dust(Vector v, Color a, Color b, float size) {
        dust(v.getX(), v.getY(), v.getZ(), a, b, size);
    }

    private void dust(double x, double y, double z, Color a, Color b, float size) {
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, x, y, z, 1, 0, 0, 0, 0,
                new Particle.DustTransition(a, b, Math.max(0.3f, Math.min(4f, size))), true);
    }

    private void burst(Particle p, Vector v, int count, double dx, double dy, double dz, double speed) {
        spark(p, v, count, dx, dy, dz, speed);
    }

    private void spark(Particle p, Vector v, int count, double dx, double dy, double dz, double speed) {
        world.spawnParticle(p, v.getX(), v.getY(), v.getZ(), count, dx, dy, dz, speed, null, true);
    }

    private static Vector lerp(Vector a, Vector b, double f) {
        return new Vector(a.getX() + (b.getX() - a.getX()) * f, a.getY() + (b.getY() - a.getY()) * f,
                a.getZ() + (b.getZ() - a.getZ()) * f);
    }

    private Vector randomUnit() {
        Vector v;
        do {
            v = new Vector(rnd.nextDouble() * 2 - 1, rnd.nextDouble() * 2 - 1, rnd.nextDouble() * 2 - 1);
        } while (v.lengthSquared() < 0.01 || v.lengthSquared() > 1);
        return v.normalize();
    }
}
