package org.aevix.daemon.data;
import java.nio.file.Path;
import java.util.Map;
import org.aevix.ipc.LoadedModule;
public class DaemonState {
    private final boolean cacheReady;
    private final Map<String, LoadedModule> modules;
    public DaemonState(boolean dexObfuscate, boolean apiProtection, boolean cacheReady,
                       int managerUid, Path miscPath, Map<String, LoadedModule> modules,
                       Map scopes, Map unloadable) {
        this.cacheReady = cacheReady;
        this.modules = modules;
    }
    public boolean isCacheReady() { return cacheReady; }
    public Map<String, LoadedModule> getModules() { return modules; }
}
