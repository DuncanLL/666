package viewpoint.platform;

import gfl2.render.ManagedShaderPack;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Publish newly installed folders to the already initialized VP 0.1.7 menu. */
public final class Gfl2InstalledPackMenu {
    private static final List<String> PACKS = List.of(ManagedShaderPack.NATIVE, ManagedShaderPack.SOMBER);
    private static final AtomicReference<Path> pending = new AtomicReference<>();

    public static void installed(Path folder) { pending.set(folder); }

    public static void refresh() {
        Path folder = pending.get();
        if (folder == null) return;
        var choice = LiveSettings.find("graphics.pack");
        if (choice == null || !pending.compareAndSet(folder, null)) return;
        var modern = new ArrayList<>(Graphics.modernPacks());
        if (PACKS.stream().allMatch(id -> modern.stream().anyMatch(pack -> pack.id.equals(id)))) return;
        try {
            Method discover = method(ShaderPacks.class, "player", String.class, Map.class, Set.class);
            Method declareOptions = method(Graphics.class, "declareOptions", ShaderPack.class);
            Method declarePack = method(Graphics.class, "declarePack");
            Field modernField = field(Graphics.class, "modern");
            Map<?, ?> settings = (Map<?, ?>)field(Graphics.class, "options").get(null);
            Map<String, File> directories = new LinkedHashMap<>();
            for (String id : PACKS) {
                File directory = folder.resolve(id).toFile();
                if (new File(directory, "pack.properties").isFile()) directories.put(id, directory);
            }
            int before = modern.size();
            for (String id : directories.keySet()) {
                if (modern.stream().anyMatch(pack -> pack.id.equals(id))) continue;
                // Use VP's own parser without clearing its existing folder and packaged shader registries.
                ShaderPack pack = (ShaderPack)discover.invoke(null, id, directories, new HashSet<String>());
                if (pack == null || ShaderPacks.isPackaged(pack)) continue;
                if (!settings.containsKey(id)) declareOptions.invoke(null, pack);
                modern.add(pack);
            }
            if (modern.size() != before) {
                String selected = choice.text();
                modernField.set(null, List.copyOf(modern));
                declarePack.invoke(null);
                LiveSettings.put(choice.key, selected);
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            System.err.println("[GFL2] Shader packs installed; restart Viewpoint if they are missing from the menu: " + failure);
        }
    }

    private static Method method(Class<?> owner, String name, Class<?>... parameters) throws ReflectiveOperationException {
        Method method = owner.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private Gfl2InstalledPackMenu() {}
}
