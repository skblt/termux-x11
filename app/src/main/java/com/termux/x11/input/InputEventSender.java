// Copyright 2016 The Chromium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package com.termux.x11.input;

import android.graphics.PointF;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import androidx.core.math.MathUtils;

import java.util.Set;
import java.util.TreeSet;

/**
 * A set of functions to send users' activities, which are represented by Android classes, to
 * remote host machine. This class uses a {@link InputStub} to do the real injections.
 */
public final class InputEventSender {
    private static final int XI_TouchBegin = 18;
    private static final int XI_TouchUpdate = 19;
    private static final int XI_TouchEnd = 20;

    private final InputStub mInjector;

    public boolean preferScancodes = false;
    public boolean pointerCapture = false;

    /** Set of pressed keys for which we've sent TextEvent. */
    private final Set<Integer> mPressedTextKeys;

    public InputEventSender(InputStub injector) {
        if (injector == null)
            throw new NullPointerException();
        mInjector = injector;
        mPressedTextKeys = new TreeSet<>();
    }

    public void sendMouseEvent(PointF pos, int button, boolean down, boolean relative) {
        if (!(button == InputStub.BUTTON_UNDEFINED
                || button == InputStub.BUTTON_LEFT
                || button == InputStub.BUTTON_MIDDLE
                || button == InputStub.BUTTON_RIGHT))
            throw new IllegalStateException();
        mInjector.sendMouseEvent((int) pos.x, (int) pos.y, button, down, relative);
    }

    public void sendMouseDown(int button, boolean relative) {
        mInjector.sendMouseEvent(0, 0, button, true, relative);
    }

    public void sendMouseUp(int button, boolean relative) {
        mInjector.sendMouseEvent(0, 0, button, false, relative);
    }

    public void sendMouseClick(int button, boolean relative) {
        mInjector.sendMouseEvent(0, 0, button, true, relative);
        mInjector.sendMouseEvent(0, 0, button, false, relative);
    }

    public void sendCursorMove(float x, float y, boolean relative) {
        mInjector.sendMouseEvent(x, y, InputStub.BUTTON_UNDEFINED, false, relative);
    }

    public void sendMouseWheelEvent(float distanceX, float distanceY) {
        mInjector.sendMouseWheelEvent(distanceX, distanceY);
    }

    final boolean[] pointers = new boolean[10];
    /**
     * Extracts the touch point data from a MotionEvent, converts each point into a marshallable
     * object and passes the set of points to the JNI layer to be transmitted to the remote host.
     *
     * @param event The event to send to the remote host for injection.  NOTE: This object must be
     *              updated to represent the remote machine's coordinate system before calling this
     *              function.
     */
    public void sendTouchEvent(MotionEvent event, RenderData renderData) {
        int action = event.getActionMasked();

        if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_HOVER_MOVE || action == MotionEvent.ACTION_HOVER_ENTER || action == MotionEvent.ACTION_HOVER_EXIT) {
            // In order to process all of the events associated with an ACTION_MOVE event, we need
            // to walk the list of historical events in order and add each event to our list, then
            // retrieve the current move event data.
            int pointerCount = event.getPointerCount();

            for (int p = 0; p < pointerCount; p++)
                pointers[event.getPointerId(p)] = false;

            for (int p = 0; p < pointerCount; p++) {
                int x = MathUtils.clamp((int) (event.getX(p) * renderData.scale.x), 0, renderData.screenWidth);
                int y = MathUtils.clamp((int) (event.getY(p) * renderData.scale.y), 0, renderData.screenHeight);
                pointers[event.getPointerId(p)] = true;
                mInjector.sendTouchEvent(XI_TouchUpdate, event.getPointerId(p), x, y);
            }

            // Sometimes Android does not send ACTION_POINTER_UP/ACTION_UP so some pointers are "stuck" in pressed state.
            for (int p = 0; p < 10; p++) {
                if (!pointers[p])
                    mInjector.sendTouchEvent(XI_TouchEnd, p, 0, 0);
            }
        } else {
            // For all other events, we only want to grab the current/active pointer.  The event
            // contains a list of every active pointer but passing all of of these to the host can
            // cause confusion on the remote OS side and result in broken touch gestures.
            int activePointerIndex = event.getActionIndex();
            int id = event.getPointerId(activePointerIndex);
            int x =  MathUtils.clamp((int) (event.getX(activePointerIndex) * renderData.scale.x), 0, renderData.screenWidth);
            int y =  MathUtils.clamp((int) (event.getY(activePointerIndex) * renderData.scale.y), 0, renderData.screenHeight);
            int a = (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) ? XI_TouchBegin : XI_TouchEnd;
            if (a == XI_TouchEnd)
                mInjector.sendTouchEvent(XI_TouchUpdate, id, x, y);
            mInjector.sendTouchEvent(a, id, x, y);
        }
    }

    /**
     * Converts the {@link KeyEvent} into low-level events and sends them to the host as either
     * key-events or text-events. This contains some logic for handling some special keys, and
     * avoids sending a key-up event for a key that was previously injected as a text-event.
     */
    public boolean sendKeyEvent(View view, KeyEvent event) {
        int keyCode = event.getKeyCode();
        boolean pressed = event.getAction() == KeyEvent.ACTION_DOWN;

        // Events received from software keyboards generate TextEvent in two
        // cases:
        //   1. This is an ACTION_MULTIPLE event.
        //   2. Ctrl, Alt and Meta are not pressed.
        // This ensures that on-screen keyboard always injects input that
        // correspond to what user sees on the screen, while physical keyboard
        // acts as if it is connected to the remote host.
        if (event.getAction() == KeyEvent.ACTION_MULTIPLE) {
            mInjector.sendTextEvent(event.getCharacters());
            return true;
        }

        // For Enter getUnicodeChar() returns 10 (line feed), but we still
        // want to send it as KeyEvent.
        int unicode = keyCode != KeyEvent.KEYCODE_ENTER ? event.getUnicodeChar() : 0;
        int scancode = preferScancodes ? event.getScanCode(): 0;

        if (!preferScancodes) {
            boolean no_modifiers =
                    !event.isAltPressed() && !event.isCtrlPressed() && !event.isMetaPressed();

            if (pressed && unicode != 0 && no_modifiers) {
                mPressedTextKeys.add(keyCode);
                mInjector.sendUnicodeEvent(unicode);
                return true;
            }

            if (!pressed && mPressedTextKeys.contains(keyCode)) {
                mPressedTextKeys.remove(keyCode);
                return true;
            }
        }

        switch (keyCode) {
            // KEYCODE_AT, KEYCODE_POUND, KEYCODE_STAR and KEYCODE_PLUS are
            // deprecated, but they still need to be here for older devices and
            // third-party keyboards that may still generate these events. See
            // https://source.android.com/devices/input/keyboard-devices.html#legacy-unsupported-keys
            case KeyEvent.KEYCODE_AT:
                mInjector.sendKeyEvent(0, KeyEvent.KEYCODE_SHIFT_LEFT, pressed);
                mInjector.sendKeyEvent(0, KeyEvent.KEYCODE_2, pressed);
                return true;

            case KeyEvent.KEYCODE_POUND:
                mInjector.sendKeyEvent(0, KeyEvent.KEYCODE_SHIFT_LEFT, pressed);
                mInjector.sendKeyEvent(0, KeyEvent.KEYCODE_3, pressed);
                return true;

            case KeyEvent.KEYCODE_STAR:
                mInjector.sendKeyEvent(0, KeyEvent.KEYCODE_SHIFT_LEFT, pressed);
                mInjector.sendKeyEvent(0, KeyEvent.KEYCODE_8, pressed);
                return true;

            case KeyEvent.KEYCODE_PLUS:
                mInjector.sendKeyEvent(0, KeyEvent.KEYCODE_SHIFT_LEFT, pressed);
                mInjector.sendKeyEvent(0, KeyEvent.KEYCODE_EQUALS, pressed);
                return true;

            default:
                // Ignoring Android's autorepeat.
                if (event.getRepeatCount() > 0)
                    return true;

                if (pointerCapture && keyCode == KeyEvent.KEYCODE_ESCAPE && !pressed)
                    view.releasePointerCapture();

                // We try to send all other key codes to the host directly.
                return mInjector.sendKeyEvent(scancode, keyCode, pressed);
        }
    }
}
