#!/usr/bin/env bash
# Compile the whole app locally without the container, against hand-written stubs of the platform
# classes, plus the real pure-class check.
#
# Why this exists: the real build needs the container (NDK r16b, build-tools, the API 28 jar). That
# is the right way to produce an APK, but it is a slow loop for catching a typo, and it caught a
# mistake this script did not: a class named Camera in this package shadowing its own
# `import android.hardware.Camera`. The lesson was not "the script is useless" but "the stubs were
# incomplete" — there was no android.hardware.Camera to collide with. The stubs below are therefore
# shaped like the real android.jar for everything this app touches, including that class.
#
# What it proves, and what it does not:
#   proves — every file compiles; the seven decision classes compile with NO android.jar at all, so
#            an android.* import in one of them fails here first; the font covers every character.
#   does not prove — anything about how the camera behaves. Only a power-cycled run on a body does
#            that (docs/PLAN.md §7).
#
#   tools/dev-verify.sh
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -n "${JAVA_HOME:-}" ]; then JAVA="$JAVA_HOME/bin"; else JAVA="$(dirname "$(command -v javac 2>/dev/null || echo /nonexistent/javac)")"; fi
[ -x "$JAVA/javac" ] || { echo "dev-verify: no javac -- set JAVA_HOME to a JDK 17" >&2; exit 1; }
JAVA_VERSION="$("$JAVA/javac" -version 2>&1)"
case "$JAVA_VERSION" in
  *" 17."*) ;;
  *) echo "dev-verify: javac is '$JAVA_VERSION'; this project builds with 17" >&2; exit 1 ;;
esac

# shellcheck source=tools/config.sh
. tools/config.sh
OUT=out/dev
STUBS="$OUT/stubs"

say() { printf '\033[1m==> %s\033[0m\n' "$*"; }

# ---------------------------------------------------------------- the stubs
# Only the surface this app uses, with the real signatures. A stub that is easier than the platform
# is a stub that hides a bug.
write_stubs() {
    mkdir -p "$STUBS"/android/{app,content,content/res,hardware,os,graphics,view,widget} \
             "$STUBS"/com/sony/scalar/sysutil "$STUBS"/$APP_PACKAGE_PATH

    cat > "$STUBS/android/content/Context.java" <<'EOF'
package android.content;
import android.content.res.AssetManager;
import java.io.File;
public class Context {
    public Intent registerReceiver(BroadcastReceiver r, IntentFilter f) { return null; }
    public AssetManager getAssets() { return null; }
    public File getFilesDir() { return null; }
    // API 8: the platform's own directory for this app on the card, which is the first place a dump tries.
    public File getExternalFilesDir(String type) { return null; }
}
EOF
    cat > "$STUBS/android/content/BroadcastReceiver.java" <<'EOF'
package android.content;
public class BroadcastReceiver { }
EOF
    cat > "$STUBS/android/content/Intent.java" <<'EOF'
package android.content;
public class Intent {
    public Intent() { }
    public Intent(String action) { }
    public static final String ACTION_BATTERY_CHANGED = "android.intent.action.BATTERY_CHANGED";
    public int getIntExtra(String name, int fallback) { return fallback; }
}
EOF
    cat > "$STUBS/android/content/IntentFilter.java" <<'EOF'
package android.content;
public class IntentFilter { public IntentFilter(String action) { } }
EOF
    cat > "$STUBS/android/content/res/Resources.java" <<'EOF'
package android.content.res;
public class Resources { public int getColor(int id) { return 0; } }
EOF
    cat > "$STUBS/android/content/res/AssetManager.java" <<'EOF'
package android.content.res;
public class AssetManager {
    // Both exist since API 1. list() is how the app finds out whether an asset was packaged at all.
    public java.io.InputStream open(String fileName) throws java.io.IOException { return null; }
    public String[] list(String path) throws java.io.IOException { return null; }
}
EOF
    # The class whose absence let a naming collision through: it must exist, with the name aapt
    # generates and the nested Parameters type the app reflects against.
    cat > "$STUBS/android/hardware/Camera.java" <<'EOF'
package android.hardware;
import android.view.SurfaceHolder;
public class Camera {
    public static Camera open() { return null; }
    public void release() { }
    public Parameters getParameters() { return null; }
    public void setParameters(Parameters p) { }
    public void setPreviewDisplay(SurfaceHolder h) throws java.io.IOException { }
    public void startPreview() { }
    public void stopPreview() { }
    public void takePicture(Object a, Object b, Object c) { }
    public static class Parameters {
        public String flatten() { return null; }
        public void unflatten(String flat) { }
        public void set(String key, String value) { }
        public String get(String key) { return null; }
        public void setFocusMode(String mode) { }
    }
}
EOF
    cat > "$STUBS/android/os/BatteryManager.java" <<'EOF'
package android.os;
public class BatteryManager {
    public static final String EXTRA_LEVEL = "level";
    public static final String EXTRA_SCALE = "scale";
    public static final String EXTRA_PLUGGED = "plugged";
}
EOF
    cat > "$STUBS/android/os/Environment.java" <<'EOF'
package android.os;
import java.io.File;
public class Environment { public static File getExternalStorageDirectory() { return null; } }
EOF
    cat > "$STUBS/android/os/StatFs.java" <<'EOF'
package android.os;
public class StatFs {
    public StatFs(String path) { }
    // API 10 has these two; getAvailableBytes() only arrived in API 18 and must not be used.
    public int getAvailableBlocks() { return 0; }
    public int getBlockSize() { return 0; }
}
EOF
    cat > "$STUBS/android/os/SystemClock.java" <<'EOF'
package android.os;
public class SystemClock { public static long elapsedRealtime() { return 0; } }
EOF
    cat > "$STUBS/android/os/Bundle.java" <<'EOF'
package android.os;
public class Bundle { }
EOF
    cat > "$STUBS/android/os/Handler.java" <<'EOF'
package android.os;
public class Handler {
    public boolean post(Runnable r) { return true; }
    public boolean postDelayed(Runnable r, long delayMs) { return true; }
    public void removeCallbacks(Runnable r) { }
}
EOF
    cat > "$STUBS/android/app/Activity.java" <<'EOF'
package android.app;
import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Bundle;
import android.view.View;
// The real Activity reaches View through ContextThemeWrapper, which is why onKeyDown can be
// overridden there. This stub carries the two methods directly for the same reason.
public class Activity extends Context {
    protected void onCreate(Bundle b) { }
    protected void onResume() { }
    protected void onPause() { }
    public void setContentView(View v) { }
    // API 1, and the exit rule hangs off it: settings are put back only when the app is really going away.
    public boolean isFinishing() { return false; }
    public Resources getResources() { return null; }
    public AssetManager getAssets() { return null; }
    public void finish() { }
    public String getPackageName() { return null; }
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) { return false; }
    public boolean onKeyUp(int keyCode, android.view.KeyEvent event) { return false; }
}
EOF
    cat > "$STUBS/android/graphics/Color.java" <<'EOF'
package android.graphics;
public class Color { public static final int BLACK = 0; public static final int WHITE = -1; }
EOF
    cat > "$STUBS/android/graphics/Paint.java" <<'EOF'
package android.graphics;
public class Paint {
    public static final int ANTI_ALIAS_FLAG = 1;
    public Paint() { }
    public Paint(int flags) { }
    public void setColor(int c) { }
    public void setTextSize(float size) { }
    public void setFakeBoldText(boolean b) { }
    public void setTypeface(Typeface t) { }
    public float measureText(String s) { return 0; }
    public float measureText(String s, int start, int end) { return 0; }
    // API 1 onwards. getFontMetrics() is API 29 and is deliberately absent.
    public float ascent() { return 0; }
    public float descent() { return 0; }
}
EOF
    cat > "$STUBS/android/graphics/Typeface.java" <<'EOF'
package android.graphics;
import android.content.res.AssetManager;
public class Typeface {
    public static final Typeface DEFAULT = new Typeface();
    public static Typeface createFromAsset(AssetManager mgr, String path) { return DEFAULT; }
    // API 1 onwards, and the fallback route when the asset path is refused.
    public static Typeface createFromFile(java.io.File file) { return DEFAULT; }
}
EOF
    cat > "$STUBS/android/graphics/Path.java" <<'EOF'
package android.graphics;
public class Path {
    public void reset() { }
    public void moveTo(float x, float y) { }
    public void lineTo(float x, float y) { }
    public void close() { }
}
EOF
    cat > "$STUBS/android/graphics/Canvas.java" <<'EOF'
package android.graphics;
public class Canvas {
    public void drawColor(int c) { }
    public void drawText(String text, float x, float y, Paint p) { }
    public void drawRect(float l, float t, float r, float b, Paint p) { }
    public void drawPath(Path p, Paint paint) { }
}
EOF
    cat > "$STUBS/android/view/View.java" <<'EOF'
package android.view;
import android.content.Context;
import android.graphics.Canvas;
public class View {
    public View(Context c) { }
    public void setFocusable(boolean b) { }
    public void setFocusableInTouchMode(boolean b) { }
    public void invalidate() { }
    // API 1 onwards: queues work behind the next draw, which is how the app keeps startup off the
    // first frame.
    public boolean post(Runnable action) { return true; }
    public boolean postDelayed(Runnable action, long delayMs) { return true; }
    public int getWidth() { return 0; }
    public int getHeight() { return 0; }
    protected void onSizeChanged(int w, int h, int oldw, int oldh) { }
    protected void onDraw(Canvas c) { }
    public boolean onKeyDown(int keyCode, KeyEvent event) { return false; }
    public boolean onKeyUp(int keyCode, KeyEvent event) { return false; }
}
EOF
    cat > "$STUBS/android/view/KeyEvent.java" <<'EOF'
package android.view;
public class KeyEvent {
    public int getScanCode() { return 0; }
    public int getRepeatCount() { return 0; }
}
EOF
    # The preview surface the capture needs. The app hands this to Camera.setPreviewDisplay, so the
    # stub carries the callback interface and the type constant the shell adds.
    cat > "$STUBS/android/view/SurfaceHolder.java" <<'EOF'
package android.view;
public interface SurfaceHolder {
    int SURFACE_TYPE_PUSH_BUFFERS = 3;
    void setType(int type);
    void addCallback(Callback cb);
    interface Callback {
        void surfaceCreated(SurfaceHolder holder);
        void surfaceChanged(SurfaceHolder holder, int format, int width, int height);
        void surfaceDestroyed(SurfaceHolder holder);
    }
}
EOF
    cat > "$STUBS/android/view/SurfaceView.java" <<'EOF'
package android.view;
import android.content.Context;
public class SurfaceView extends View {
    public SurfaceView(Context c) { super(c); }
    public SurfaceHolder getHolder() { return null; }
}
EOF
    cat > "$STUBS/android/view/ViewGroup.java" <<'EOF'
package android.view;
import android.content.Context;
public class ViewGroup extends View {
    public ViewGroup(Context c) { super(c); }
    public void addView(View child, LayoutParams params) { }
    public static class LayoutParams {
        public static final int MATCH_PARENT = -1;
        public LayoutParams(int width, int height) { }
    }
}
EOF
    cat > "$STUBS/android/widget/FrameLayout.java" <<'EOF'
package android.widget;
import android.content.Context;
import android.view.ViewGroup;
public class FrameLayout extends ViewGroup {
    public FrameLayout(Context c) { super(c); }
    public static class LayoutParams extends ViewGroup.LayoutParams {
        public LayoutParams(int width, int height) { super(width, height); }
    }
}
EOF
    cat > "$STUBS/com/sony/scalar/sysutil/ScalarProperties.java" <<'EOF'
package com.sony.scalar.sysutil;
public class ScalarProperties { public static String getString(String key) { return null; } }
EOF
    # R.java, in the shape aapt generates for our res/values/colors.xml.
    cat > "$STUBS/$APP_PACKAGE_PATH/R.java" <<'EOF'
package com.voxivoid.bettertimelapse;
public final class R {
    public static final class color {
        public static final int background = 0x7f020000, text = 0x7f020001, dim = 0x7f020002,
                selected = 0x7f020003, accent = 0x7f020004, warn = 0x7f020005, good = 0x7f020006;
    }
    public static final class string { public static final int app_name = 0x7f030000; }
    public static final class drawable { public static final int ic_launcher = 0x7f040000; }
}
EOF
}

# ---------------------------------------------------------------- run
rm -rf "$OUT"
mkdir -p "$OUT/pure" "$OUT/all"
write_stubs

say "[1/4] the decision classes, with no android.jar"
UNIT_FILES=()
for u in ${UNITS[@]+"${UNITS[@]}"}; do UNIT_FILES+=("$SOURCE_DIR/$APP_PACKAGE_PATH/$u.java"); done
"$JAVA/javac" -encoding UTF-8 --release 8 -Xlint:-options -d "$OUT/pure" "${UNIT_FILES[@]}"
echo "      ${#UNIT_FILES[@]} classes compiled with no Android on the classpath"

say "[2/4] every source file, against the stubs"
"$JAVA/javac" -encoding UTF-8 --release 8 -Xlint:-options -d "$OUT/all" \
    $(find "$STUBS" -name '*.java') "$SOURCE_DIR/$APP_PACKAGE_PATH"/*.java
echo "      $(ls "$SOURCE_DIR/$APP_PACKAGE_PATH"/*.java | wc -l) source files compiled"

say "[3/4] the bundled font and the asset path the code asks for"
if command -v python3 >/dev/null 2>&1; then
    python3 tools/check-asset-path.py --pre-build
fi
if command -v python3 >/dev/null 2>&1; then
    python3 tools/check-font.py
else
    echo "      SKIPPED: no python3"
fi

echo
echo "DEV VERIFY OK — this proves it compiles, not that the camera behaves."
