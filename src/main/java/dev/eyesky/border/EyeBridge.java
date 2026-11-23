package dev.eyesky.border;

import dev.eyesky.EyeManager;
import org.bukkit.World;
import org.bukkit.util.Vector;

import java.lang.reflect.Method;

/** Доступ к package-private методам EyeManager через reflection. */
final class EyeBridge {
    private final EyeManager eye;
    private final Method mWorld, mPos, mHold, mHoldSpeed, mTeleport;

    EyeBridge(EyeManager eye) throws ReflectiveOperationException {
        this.eye = eye;
        mWorld = EyeManager.class.getDeclaredMethod("world");
        mPos = EyeManager.class.getDeclaredMethod("position");
        mHold = EyeManager.class.getDeclaredMethod("setHold", Vector.class);
        mHoldSpeed = EyeManager.class.getDeclaredMethod("setHoldSpeed", double.class);
        mTeleport = EyeManager.class.getDeclaredMethod("teleportEye", Vector.class);
        for (Method m : new Method[]{mWorld, mPos, mHold, mHoldSpeed, mTeleport}) m.setAccessible(true);
    }

    World world() {
        try { return (World) mWorld.invoke(eye); } catch (ReflectiveOperationException e) { return null; }
    }

    Vector position() {
        try {
            Vector v = (Vector) mPos.invoke(eye);
            return v == null ? null : v.clone();
        } catch (ReflectiveOperationException e) { return null; }
    }

    void hold(Vector v) {
        try { mHold.invoke(eye, v == null ? null : v.clone()); } catch (ReflectiveOperationException ignored) { }
    }

    void holdSpeed(double s) {
        try { mHoldSpeed.invoke(eye, s); } catch (ReflectiveOperationException ignored) { }
    }

    void teleport(Vector v) {
        try { mTeleport.invoke(eye, v.clone()); } catch (ReflectiveOperationException ignored) { }
    }
}
