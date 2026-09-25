/*
 * Enginehost integration for the Adventure Game Studio Android runtime.
 * Copyright (c) 2026 the Enginehost contributors. MIT License; see LICENSE.
 */

package uk.co.adventuregamestudio.agsplayer;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import androidx.annotation.Keep;

import org.json.JSONException;
import org.json.JSONObject;
import org.libsdl.app.SDLActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import uk.co.adventuregamestudio.runtime.AGSRuntimeActivity;

/**
 * Runs an AGS game Enginehost hands over, in place, on the official AGS
 * engine: the game's data file (a 3.5+ .ags, or the .exe an older game
 * carries its data in) is given to the engine as its command-line game path.
 *
 * Saves: AGS saves in the system's saved-games folder, in a subfolder the
 * game names (Windows: Saved Games\&lt;game&gt;; the engine's XDG platforms:
 * $XDG_DATA_HOME/ags/&lt;game&gt;). The upstream Android port writes into the
 * game folder instead. Enginehost does not change where a game saves; it
 * makes that system folder mean the save folder it hands the runtime, which
 * the engine's Android platform reads from ENGINEHOST_AGS_SAVE_ROOT.
 *
 * Controller: AGS has no pad model of its own; games read the mouse and the
 * keyboard. Enginehost's map for AGS is therefore AGS's own inputs under the
 * engine's own names (eMouseLeft, eKeyEscape, ...), and this class turns
 * each bound pad control into that mouse button, wheel step, key or pointer
 * movement. Touch keeps the upstream port's touch-to-mouse emulation.
 */
@Keep
public final class EngineHostAgsActivity extends AGSRuntimeActivity {
    private static final String TAG = "AGS[Enginehost]";
    private static final String EXTRA = "dev.enginehost.runtime.";
    private static final String ERROR = "dev.enginehost.runtime.ERROR";
    private static final float DIGITAL_THRESHOLD = 0.5f;
    private static final float POINTER_DEADZONE = 0.15f;
    /** Full deflection crosses the longer screen side in about this long. */
    private static final float POINTER_CROSSING_SECONDS = 1.2f;
    private static final long POINTER_FRAME_MS = 16;

    private static final int MOUSE_LEFT = 1;
    private static final int MOUSE_RIGHT = 2;
    private static final int MOUSE_MIDDLE = 4;

    /** AGS's keys, as the Android key SDL reports to the engine for each. */
    private static final Map<String, Integer> KEYS = new HashMap<>();
    /** AGS's mouse buttons, as SDL's Android button bits. */
    private static final Map<String, Integer> BUTTONS = new HashMap<>();

    static {
        KEYS.put("ags_key_escape", KeyEvent.KEYCODE_ESCAPE);
        KEYS.put("ags_key_return", KeyEvent.KEYCODE_ENTER);
        KEYS.put("ags_key_space", KeyEvent.KEYCODE_SPACE);
        KEYS.put("ags_key_tab", KeyEvent.KEYCODE_TAB);
        KEYS.put("ags_key_up", KeyEvent.KEYCODE_DPAD_UP);
        KEYS.put("ags_key_down", KeyEvent.KEYCODE_DPAD_DOWN);
        KEYS.put("ags_key_left", KeyEvent.KEYCODE_DPAD_LEFT);
        KEYS.put("ags_key_right", KeyEvent.KEYCODE_DPAD_RIGHT);
        KEYS.put("ags_key_f5", KeyEvent.KEYCODE_F5);
        KEYS.put("ags_key_f7", KeyEvent.KEYCODE_F7);
        BUTTONS.put("ags_mouse_left", MOUSE_LEFT);
        BUTTONS.put("ags_mouse_right", MOUSE_RIGHT);
        BUTTONS.put("ags_mouse_middle", MOUSE_MIDDLE);
    }

    private static final String WHEEL_NORTH = "ags_wheel_north";
    private static final String WHEEL_SOUTH = "ags_wheel_south";
    private static final String POINTER_X = "ags_pointer_x";
    private static final String POINTER_Y = "ags_pointer_y";

    /** One binding from the host's map: a key, a signed axis (digital), a whole axis (analogue), or nothing. */
    private static final class Binding {
        final String type;
        final int code;
        final int direction;

        Binding(String type, int code, int direction) {
            this.type = type;
            this.code = code;
            this.direction = direction;
        }

        static Binding parse(JSONObject json) {
            String type = json.optString("type", "none");
            if ("key".equals(type)) return new Binding(type, json.optInt("code"), 0);
            if ("axis".equals(type)) return new Binding(type, json.optInt("axis"), json.optInt("direction", 0));
            return new Binding("none", 0, 0);
        }
    }

    private Map<String, Binding> bindings = new HashMap<>();
    private final Set<String> held = new HashSet<>();
    private final Set<Integer> keysDown = new HashSet<>();
    private int mouseButtons;
    private float pointerX = -1f;
    private float pointerY = -1f;
    private float stickX;
    private float stickY;
    private long lastPointerFrame;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pointerFrame = this::movePointer;

    @Override
    protected void onCreate(Bundle state) {
        Intent intent = getIntent();
        String failure = prepare(intent);
        if (failure != null) {
            Log.e(TAG, failure);
            setResult(RESULT_FIRST_USER, new Intent().putExtra(ERROR, failure));
            finish();
            // Android requires onCreate to reach Activity's; the engine's runs
            // with no game on an activity that is already finishing.
            intent.putExtra("filename", "");
        }
        super.onCreate(state);
    }

    /** Hands the engine its game, save root and private folder; null on success, else what is wrong. */
    private String prepare(Intent intent) {
        String path = intent.getStringExtra(EXTRA + "PATH");
        File folder = path == null ? null : new File(path);
        if (folder == null || !folder.isDirectory()) return "Enginehost did not provide a valid game folder";
        String exec = intent.getStringExtra(EXTRA + "EXEC_FILE");
        File data = exec == null || exec.isEmpty() ? findGameData(folder) : new File(folder, exec);
        if (data == null || !data.isFile()) {
            return "No AGS game data (a .ags file, ac2game.dat or the game's .exe) in " + folder.getPath();
        }
        String save = intent.getStringExtra(EXTRA + "SAVE_PATH");
        try {
            if (save != null && !save.isEmpty()) Os.setenv("ENGINEHOST_AGS_SAVE_ROOT", save, true);
        } catch (ErrnoException error) {
            return "Could not hand the save folder to AGS: " + error.getMessage();
        }
        // The engine's own files (its global config and log) go to a folder of
        // the runtime's, never into the game folder, which stays as shipped.
        File own = new File(getFilesDir(), "enginehost/ags");
        if (!own.isDirectory() && !own.mkdirs()) return "Could not create AGS's own folder " + own.getPath();
        intent.putExtra("filename", data.getPath());
        intent.putExtra("directory", own.getPath());
        intent.putExtra("loadLastSave", false);
        bindings = parseBindings(intent.getStringExtra(EXTRA + "CONTROLLER_BINDINGS"));
        return null;
    }

    /** The data file the engine would find itself: a .ags, then ac2game.dat, then the game's exe. */
    private static File findGameData(File folder) {
        File[] files = folder.listFiles();
        if (files == null) return null;
        Arrays.sort(files);
        List<File> executables = new ArrayList<>();
        File datFile = null;
        for (File file : files) {
            String name = file.getName().toLowerCase(Locale.ROOT);
            if (!file.isFile()) continue;
            if (name.endsWith(".ags")) return file;
            if (name.equals("ac2game.dat")) datFile = file;
            if (name.endsWith(".exe") && !name.equals("winsetup.exe")) executables.add(file);
        }
        if (datFile != null) return datFile;
        File largest = null;
        for (File exe : executables) if (largest == null || exe.length() > largest.length()) largest = exe;
        return largest;
    }

    private static Map<String, Binding> parseBindings(String raw) {
        Map<String, Binding> parsed = new HashMap<>();
        if (raw == null || raw.isEmpty()) return parsed;
        try {
            JSONObject json = new JSONObject(raw);
            for (Iterator<String> keys = json.keys(); keys.hasNext(); ) {
                String id = keys.next();
                JSONObject binding = json.optJSONObject(id);
                if (binding != null) parsed.put(id, Binding.parse(binding));
            }
        } catch (JSONException error) {
            Log.w(TAG, "Unreadable controller map; the pad does nothing in this game", error);
        }
        return parsed;
    }

    /** The engine library is loaded by name: the runtime runs inside Enginehost, whose native library folder is not this bundle's. */
    @Override
    protected String getMainSharedObject() {
        String[] libraries = getLibraries();
        return "lib" + libraries[libraries.length - 1] + ".so";
    }

    private static boolean fromPad(int source) {
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
            || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
            || (source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
    }

    /**
     * Pad keys become the AGS inputs they are bound to. Every other key goes
     * to SDL from the activity rather than through view focus, which a runtime
     * behind Enginehost's manifest proxy cannot rely on; Back stays the
     * upstream port's in-game menu.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (mSurface == null || keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(event);
        }
        if (fromPad(event.getSource())) {
            if (event.getRepeatCount() > 0) return true;
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            if (down) keysDown.add(keyCode); else keysDown.remove(keyCode);
            digitalFromKey(keyCode, down);
            return true;
        }
        if (SDLActivity.handleKeyEvent(mSurface, keyCode, event, null)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK
                || event.getActionMasked() != MotionEvent.ACTION_MOVE) {
            return super.dispatchGenericMotionEvent(event);
        }
        // A d-pad that reports as the hat axes presses the same keys a d-pad
        // that reports keys does, so one binding serves both kinds of pad.
        hat(event.getAxisValue(MotionEvent.AXIS_HAT_X), KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT);
        hat(event.getAxisValue(MotionEvent.AXIS_HAT_Y), KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN);
        for (Map.Entry<String, Binding> entry : bindings.entrySet()) {
            Binding binding = entry.getValue();
            if (!"axis".equals(binding.type) || binding.direction == 0) continue;
            setDigital(entry.getKey(), event.getAxisValue(binding.code) * binding.direction > DIGITAL_THRESHOLD);
        }
        stickX = analogue(POINTER_X, event);
        stickY = analogue(POINTER_Y, event);
        schedulePointer();
        return true;
    }

    private float analogue(String action, MotionEvent event) {
        Binding binding = bindings.get(action);
        if (binding == null || !"axis".equals(binding.type) || binding.direction != 0) return 0f;
        return event.getAxisValue(binding.code);
    }

    private void hat(float value, int negative, int positive) {
        for (int key : new int[] {negative, positive}) {
            boolean down = key == negative ? value < -DIGITAL_THRESHOLD : value > DIGITAL_THRESHOLD;
            if (down == keysDown.contains(key)) continue;
            if (down) keysDown.add(key); else keysDown.remove(key);
            digitalFromKey(key, down);
        }
    }

    private void digitalFromKey(int keyCode, boolean down) {
        for (Map.Entry<String, Binding> entry : bindings.entrySet()) {
            Binding binding = entry.getValue();
            if ("key".equals(binding.type) && binding.code == keyCode) setDigital(entry.getKey(), down);
        }
    }

    /** One AGS input going down or up, reported once per change so two controls bound to it do not stutter it. */
    private void setDigital(String action, boolean down) {
        if (down == held.contains(action)) return;
        if (down) held.add(action); else held.remove(action);
        Integer key = KEYS.get(action);
        if (key != null) {
            if (down) SDLActivity.onNativeKeyDown(key); else SDLActivity.onNativeKeyUp(key);
            return;
        }
        Integer button = BUTTONS.get(action);
        if (button != null) {
            ensurePointer();
            mouseButtons = down ? mouseButtons | button : mouseButtons & ~button;
            SDLActivity.onNativeMouse(mouseButtons, down ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_UP,
                pointerX, pointerY, false);
            return;
        }
        if (down && (WHEEL_NORTH.equals(action) || WHEEL_SOUTH.equals(action))) {
            SDLActivity.onNativeMouse(0, MotionEvent.ACTION_SCROLL, 0f, WHEEL_NORTH.equals(action) ? 1f : -1f, false);
        }
    }

    /** The pointer starts in the middle of the screen, where a mouse would be left. */
    private void ensurePointer() {
        if (pointerX >= 0f) return;
        pointerX = mSurface.getWidth() / 2f;
        pointerY = mSurface.getHeight() / 2f;
    }

    private void schedulePointer() {
        if (Math.abs(stickX) < POINTER_DEADZONE && Math.abs(stickY) < POINTER_DEADZONE) return;
        handler.removeCallbacks(pointerFrame);
        lastPointerFrame = 0;
        handler.post(pointerFrame);
    }

    /** Steps the pointer by the stick every frame while it is deflected, faster the further it is pushed. */
    private void movePointer() {
        if (mSurface == null) return;
        float x = shape(stickX);
        float y = shape(stickY);
        if (x == 0f && y == 0f) return;
        long now = SystemClock.uptimeMillis();
        float seconds = lastPointerFrame == 0 ? POINTER_FRAME_MS / 1000f : (now - lastPointerFrame) / 1000f;
        lastPointerFrame = now;
        ensurePointer();
        float width = mSurface.getWidth();
        float height = mSurface.getHeight();
        float speed = Math.max(width, height) / POINTER_CROSSING_SECONDS;
        pointerX = Math.max(0f, Math.min(width - 1f, pointerX + x * speed * seconds));
        pointerY = Math.max(0f, Math.min(height - 1f, pointerY + y * speed * seconds));
        SDLActivity.onNativeMouse(mouseButtons, MotionEvent.ACTION_MOVE, pointerX, pointerY, false);
        handler.postDelayed(pointerFrame, POINTER_FRAME_MS);
    }

    private static float shape(float value) {
        float magnitude = Math.abs(value);
        if (magnitude < POINTER_DEADZONE) return 0f;
        float scaled = (magnitude - POINTER_DEADZONE) / (1f - POINTER_DEADZONE);
        return Math.signum(value) * scaled * scaled;
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(pointerFrame);
        super.onPause();
    }
}
