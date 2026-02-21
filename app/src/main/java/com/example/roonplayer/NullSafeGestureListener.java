package com.example.roonplayer;

import android.view.GestureDetector;
import android.view.MotionEvent;

import androidx.annotation.Nullable;

/**
 * Bridges GestureDetector callbacks through Java so Kotlin null-check assertions
 * do not crash when framework delivers null MotionEvent references.
 */
abstract class NullSafeGestureListener extends GestureDetector.SimpleOnGestureListener {
    @Override
    public boolean onDown(MotionEvent e) {
        return true;
    }

    @Override
    public final boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
        return onFlingNullable(e1, e2, velocityX, velocityY);
    }

    protected abstract boolean onFlingNullable(
        @Nullable MotionEvent e1,
        @Nullable MotionEvent e2,
        float velocityX,
        float velocityY
    );
}
