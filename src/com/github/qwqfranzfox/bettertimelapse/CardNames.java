package com.github.qwqfranzfox.bettertimelapse;

/**
 * What a name may look like on this camera's card.
 *
 * <p>Not a guess about the filesystem — a reading of the evidence. Every name on this card is
 * eight-dot-three: {@code TIMELPS/LOG.TXT}, the log the old TimeLapse app writes at launch;
 * {@code APPSTORE.log}, {@code TWEAKLOG.txt} and {@code PMCADEMO} at the root; {@code DSC00507.ARW} and
 * {@code 10561007} from the camera itself. This app's attempts used a 28-character file name and a
 * 15-character directory name, in six places, and every one came back {@code ENOENT} — "no such file or
 * directory" — including inside directories that demonstrably exist and are writable. A filesystem that
 * cannot represent a long name answers exactly that way: the name has nowhere to go, so it is not found.
 *
 * <p>So the rule is here rather than in the class that writes the file, and it has a test, because the next
 * edit that lengthens a name to make it more readable would bring the whole thing back with no clue why.
 *
 * <p>No {@code android.*} import: {@code tools/test.sh} compiles this without {@code android.jar}.
 */
public final class CardNames {

    private CardNames() {}

    /**
     * Whether a name fits in eight-dot-three: a base of at most eight characters, an extension of at most
     * three, and no second dot.
     *
     * <p>Case is not part of it — {@code APPSTORE.log} and {@code LOG.TXT} are both on the card — and neither
     * is what the characters are, because the camera's own directories are digits ({@code 10561007}).
     */
    public static boolean fitsEightDotThree(String name) {
        if (name == null || name.length() == 0) return false;
        int dot = name.indexOf('.');
        if (dot < 0) return name.length() <= 8;
        String base = name.substring(0, dot);
        String extension = name.substring(dot + 1);
        return base.length() > 0 && base.length() <= 8
                && extension.length() <= 3
                && extension.indexOf('.') < 0;
    }
}
