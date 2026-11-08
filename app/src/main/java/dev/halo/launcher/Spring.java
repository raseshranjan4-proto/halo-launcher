package dev.halo.launcher;

import android.animation.TimeInterpolator;

/**
 * Damped-spring easing: moves fast, slightly overshoots, then settles.
 * damping controls how quickly it settles; frequency controls the bounce.
 */
final class Spring implements TimeInterpolator {
    private final float damping, frequency, end;

    Spring(float damping, float frequency) {
        this.damping = damping;
        this.frequency = frequency;
        this.end = raw(1f);
    }

    private float raw(float t) {
        return (float) (1 - Math.exp(-damping * t) * Math.cos(frequency * t));
    }

    @Override
    public float getInterpolation(float t) {
        if (t >= 1f) return 1f;
        return raw(t) / end;   // normalized so the animation lands exactly on its target
    }
}
