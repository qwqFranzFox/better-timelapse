package com.github.qwqfranzfox.bettertimelapse;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Typeface;
import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Loads the bundled Chinese font, by every route the platform offers, and reports which one worked.
 *
 * <p>The firmware font has no CJK, so without this every Chinese label is a tofu box. That much was
 * confirmed on a camera. What is <em>not</em> known is whether Android 2.3's {@code Typeface} will
 * accept this particular file as an asset: the first version of this code called
 * {@code createFromAsset} inside a bare {@code catch} that returned {@code null}, so a failure
 * produced a screen full of boxes and no way to tell why. That is the wrong shape for a failure the
 * user can see but the developer cannot.
 *
 * <p>So this class tries the routes in order, keeps the reason for each failure, and hands the whole
 * story to the panel:
 *
 * <ol>
 *   <li>the asset, straight from the APK — the cheapest and the one that should work;</li>
 *   <li>the same file copied out of the APK into app storage, then loaded as a file. Some old
 *       {@code Typeface} implementations want a real path, and this costs one copy of ~57 KB;</li>
 *   <li>the same file from external storage, which is where the camera's own tree lives and where a
 *       user can drop a file by hand.</li>
 * </ol>
 *
 * <p>A null result means all three failed, and {@link #report()} then says exactly how.
 */
public final class UiFont {

    private UiFont() {}

    /** Where the font lives inside the APK. */
    public static final String ASSET = "fonts/BetterTimelapseCjkSC-Regular.ttf";

    /** The name it is written under when it has to become a real file. */
    private static final String CACHE_NAME = "BetterTimelapseCJK.ttf";

    /**
     * A CJK character used as the on-screen proof: it is drawn at the top of the panel, so a
     * photograph of the screen says whether the bundled font is in use without needing to read any
     * of the small text.
     */
    public static final String PROBE = "\u95f4\u9694\u5f20\u6570";   // 间隔张数

    /** The route that worked, or an empty string. */
    public static String route() {
        return route;
    }

    /** Why every route failed; empty when one of them worked. */
    public static String report() {
        return report;
    }

    private static String route = "";
    private static String report = "";

    /**
     * Load the font, trying each route in turn.
     *
     * @return the typeface, or {@code null} when every route failed; {@link #report} explains.
     */
    public static Typeface load(Context context) {
        report = "";
        route = "";

        Typeface face = fromAsset(context);
        if (face != null) return use(face, "asset");

        String assetWhy = report;
        face = fromCopiedFile(context);
        if (face != null) return use(face, "copied file");

        String fileWhy = report;
        face = fromExternal();
        if (face != null) return use(face, "external storage");

        report = "asset: " + assetWhy + "; file: " + fileWhy + "; sdcard: " + report;
        return null;
    }

    private static Typeface use(Typeface face, String which) {
        route = which;
        report = "";
        return face;
    }

    private static Typeface fromAsset(Context context) {
        try {
            Typeface face = Typeface.createFromAsset(context.getAssets(), ASSET);
            if (face == null) report = "createFromAsset returned null";
            return face;
        } catch (Throwable t) {
            report = shortName(t);
            return null;
        }
    }

    /**
     * Copy the asset to a real file and load that. {@code Typeface.createFromFile} exists since API
     * 1, and some old implementations only accept a path.
     */
    private static Typeface fromCopiedFile(Context context) {
        try {
            File out = new File(context.getFilesDir(), CACHE_NAME);
            if (!out.exists() || out.length() == 0) {
                InputStream in = context.getAssets().open(ASSET);
                if (in == null) {
                    report = "assets.open returned null";
                    return null;
                }
                OutputStream os = new FileOutputStream(out);
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) os.write(buffer, 0, n);
                os.close();
                in.close();
            }
            Typeface face = Typeface.createFromFile(out);
            if (face == null) report = "createFromFile returned null";
            return face;
        } catch (Throwable t) {
            report = shortName(t);
            return null;
        }
    }

    /** The same file, dropped on the card by hand: the escape hatch when the APK path is unusable. */
    private static Typeface fromExternal() {
        try {
            File dir = Environment.getExternalStorageDirectory();
            if (dir == null) {
                report = "no external storage";
                return null;
            }
            File f = new File(new File(dir, "bettertimelapse"), CACHE_NAME);
            if (!f.exists()) {
                report = "not on the card";
                return null;
            }
            Typeface face = Typeface.createFromFile(f);
            if (face == null) report = "createFromFile returned null";
            return face;
        } catch (Throwable t) {
            report = shortName(t);
            return null;
        }
    }

    /**
     * What is actually packaged, for the case where the asset cannot be found at all. A flat listing
     * distinguishes "not packaged" from "packaged somewhere else", which look identical on screen.
     */
    public static String assetListing(Context context) {
        StringBuilder b = new StringBuilder("assets=");
        try {
            String[] all = context.getAssets().list("");
            if (all == null || all.length == 0) {
                b.append("\u7a7a");                                   // 空
            } else {
                for (int i = 0; i < all.length; i++) {
                    if (i > 0) b.append(",");
                    b.append(all[i]);
                }
            }
            b.append(" fonts=");
            String[] fonts = context.getAssets().list("fonts");
            if (fonts == null || fonts.length == 0) {
                b.append("\u7a7a");                                   // 空
            } else {
                for (int i = 0; i < fonts.length; i++) {
                    if (i > 0) b.append(",");
                    b.append(fonts[i]);
                }
            }
        } catch (Throwable t) {
            b.append("\u8bfb\u4e0d\u51fa: ").append(shortName(t));    // 读不出
        }
        return b.toString();
    }

    private static String shortName(Throwable t) {
        String message = t.getMessage();
        String name = t.getClass().getSimpleName();
        return message == null || message.length() == 0 ? name : name + ": " + message;
    }
}
