package cz.teacherfriend.redpen;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Stav rozpracované opravy sdílený mezi obrazovkami (v rámci běhu procesu). */
public final class Session {
    private static final Session INSTANCE = new Session();

    public static Session get() {
        return INSTANCE;
    }

    /** Stránky práce uložené jako JPEG v cache (max. {@link PageLoader#MAX_EDGE} px). */
    public final List<File> pages = new ArrayList<>();
    public String instructions = "";
    public GradingResult result;

    private Session() {
    }
}
