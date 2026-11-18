package dev.lockplayer;

public final class ModuleState {
    public static volatile boolean loaded;

    private ModuleState() { }

    public static boolean loadedHere() {
        return loaded;
    }
}
