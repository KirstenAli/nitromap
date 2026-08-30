package dev.nitromap;

import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

final class ShutdownRegistry {

    private static final System.Logger LOG = System.getLogger(ShutdownRegistry.class.getName());
    private static final Set<NitroMap<?, ?>> MAPS = Collections.synchronizedSet(
            Collections.newSetFromMap(new IdentityHashMap<>()));
    private static final boolean ENABLED = register();

    private ShutdownRegistry() {
    }

    static void add(NitroMap<?, ?> map) {
        if (ENABLED) MAPS.add(map);
    }

    static void remove(NitroMap<?, ?> map) {
        MAPS.remove(map);
    }

    static void closeAll() {
        snapshot().forEach(ShutdownRegistry::close);
    }

    private static boolean register() {
        try {
            addHook();
            return true;
        } catch (IllegalStateException | SecurityException exception) {
            return unavailable(exception);
        }
    }

    private static void addHook() {
        Thread hook = new Thread(ShutdownRegistry::closeAll, "nitromap-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
    }

    private static boolean unavailable(RuntimeException exception) {
        LOG.log(System.Logger.Level.WARNING, "NitroMap shutdown hook unavailable", exception);
        return false;
    }

    private static void close(NitroMap<?, ?> map) {
        try {
            map.close();
        } catch (IOException exception) {
            LOG.log(System.Logger.Level.ERROR, "Could not close NitroMap during shutdown", exception);
        }
    }

    private static List<NitroMap<?, ?>> snapshot() {
        synchronized (MAPS) {
            return List.copyOf(MAPS);
        }
    }
}
