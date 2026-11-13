import java.io.File;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import net.bytebuddy.jar.asm.*;
import org.json.JSONObject;
import viewpoint.iris.IrisPack;
import zombie.ZomboidFileSystem;

public final class UnifiedStartupCheck {
    private static final String MODEL_PASS = "viewpoint/render/ModelPass";
    private static final List<String> ORDER = List.of("NativePmxMaterials.bind", "NativePmxMaterials.unbind",
        "AimohNativeGf2Runtime.beforeLighting");

    public static void apply(String name, Instrumentation agent) throws Exception {
        Class<?> loader = Class.forName("me.zed_0xff.zombie_buddy.Loader");
        var instrumentation = loader.getDeclaredField("g_instrumentation");
        instrumentation.setAccessible(true);
        instrumentation.set(null, agent);
        Class<?> phase = Class.forName("me.zed_0xff.zombie_buddy.Loader$Phase");
        Object main = Arrays.stream(phase.getEnumConstants()).filter(p -> p.toString().equals("MAIN")).findFirst().orElseThrow();
        var apply = loader.getDeclaredMethod("ApplyPatchesFromPackage", String.class, ClassLoader.class, phase);
        apply.setAccessible(true);
        require(Boolean.TRUE.equals(apply.invoke(null, name, UnifiedStartupCheck.class.getClassLoader(), main)), "ZombieBuddy package dispatch failed");
    }

    public static void prepare() throws Exception {
        Class<?> scene = Class.forName("viewpoint.render.SceneData");
        var ctor = scene.getDeclaredConstructor();
        ctor.setAccessible(true);
        Class.forName("aimoh.pmxpreview.RenderPatch").getDeclaredMethod("enter", scene, Object[].class)
            .invoke(null, ctor.newInstance(), new Object[0]);
    }

    private static List<String> drawOrder(Instrumentation agent) throws Exception {
        List<String> order = new ArrayList<>();
        ClassFileTransformer observer = new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String type, Class<?> klass, ProtectionDomain domain, byte[] bytes) {
                if (!MODEL_PASS.equals(type)) return null;
                new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] errors) {
                        if (!name.equals("gbuffer")) return null;
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override public void visitMethodInsn(int op, String owner, String method, String desc, boolean iface) {
                                if (owner.startsWith("aimoh/") || owner.endsWith("/AimohNativeGf2Runtime"))
                                    order.add(owner.substring(owner.lastIndexOf('/') + 1) + "." + method);
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                return null;
            }
        };
        agent.addTransformer(observer, true);
        try { agent.retransformClasses(Class.forName(MODEL_PASS.replace('/', '.'))); }
        finally { agent.removeTransformer(observer); }
        return order;
    }

    private static Object find(String registry, File directory, String id) throws Exception {
        Class<?> type = Class.forName("viewpoint.platform." + registry);
        var discover = type.getDeclaredMethod("discover", File.class);
        discover.setAccessible(true);
        discover.invoke(null, directory);
        var find = type.getDeclaredMethod("find", String.class);
        find.setAccessible(true);
        Object pack = find.invoke(null, id);
        require(pack != null, "VP did not discover " + id);
        return pack;
    }

    private static Path installOldEclipse(Path user) throws Exception {
        Path archive = user.resolve("old-managed-eclipse.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("shaders/gf2/manifest.json"));
            zip.write("{\"pipeline\":\"gf2-original-eclipse\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("shaders/world0/gbuffers_terrain.gsh"));
            zip.write("layout(triangle_strip,max_vertices=24) out;".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        var installed = aimoh.gf2.bridge.ManagedShaderPack.install(archive, user);
        require(installed.action().equals("INSTALLED"), "Could not seed an old managed Eclipse pack");
        return archive;
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createDirectories(Path.of(args[0]));
        Path user = Files.createTempDirectory(root, "玩家 空目录-");
        ZomboidFileSystem.instance.setCacheDir(user.toString());
        ZomboidFileSystem.instance.init();
        Path selection = user.resolve("viewpoint-live.properties");
        String chosen = "graphics.pack=minecraft-Aimoh-Eclipse-GF2\n";
        Files.writeString(selection, chosen);
        Path folder = Files.createDirectories(user.resolve("viewpoint-shaderpacks"));
        Path custom = folder.resolve("PlayerCustom.zip");
        Files.write(custom, new byte[]{7,8,9});
        boolean legacy = args[2].equals("legacy");
        boolean lateHost = args[2].equals("late-host");
        Instrumentation agent = RenderSupportTestAgent.agent;
        Path oldEclipse = lateHost ? installOldEclipse(user) : null;
        Path installedEclipse = folder.resolve("Aimoh-Eclipse-GF2.zip");
        if (lateHost) require(UnifiedStartupCheck.class.getClassLoader().getResource(MODEL_PASS + ".class") == null,
            "Late-host case already exposes Viewpoint");
        apply("aimoh.pmxpreview", agent);
        if (lateHost) {
            require(Files.mismatch(oldEclipse, installedEclipse) == -1, "Shader install ran before Viewpoint was added");
            agent.appendToSystemClassLoaderSearch(new JarFile(args[3]));
            apply("viewpoint", agent);
        }
        if (legacy) apply("aimoh.gf2.bridge", agent);
        List<String> early = ORDER.subList(0,2);
        var before = drawOrder(agent);
        require(before.equals(early), "Character advice not registered exactly once before first draw: "+before);
        JSONObject report = new JSONObject().put("legacy_duplicate", legacy).put("normal_zombiebuddy_dispatch", true)
            .put("viewpoint_added_after_character", lateHost);
        if (legacy) {
            prepare();
            require(drawOrder(agent).equals(early), "Legacy coexistence applied a duplicate renderer");
            require(!Files.exists(folder.resolve("Aimoh-Eclipse-GF2.zip")), "Duplicate renderer touched the user's packs");
        } else {
            if (lateHost) {
                require(Files.mismatch(oldEclipse, installedEclipse) != -1, "Stale managed Eclipse was not upgraded");
                try (var zip = new ZipFile(installedEclipse.toFile())) {
                    require(zip.stream().noneMatch(e -> e.getName().endsWith("/gbuffers_terrain.gsh")),
                        "Managed Eclipse still contains the old terrain geometry shader");
                }
                report.put("stale_eclipse_geometry_upgraded", true);
            }
            var iris = (IrisPack)find("IrisPacks", folder.toFile(), "minecraft-Aimoh-Eclipse-GF2");
            var manifest = new JSONObject(iris.source.read("gf2/manifest.json"));
            var probe = manifest.getJSONObject("native_probe");
            byte[] source = iris.source.readBytes(probe.getString("path"));
            require(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source)).equals(probe.getString("sha256")),
                "Saved Eclipse pack cannot restore its probe before first model preparation");
            var macros = viewpoint.iris.IrisMacros.standard(460,"","",Set.of("COMPUTE_SHADERS","CUSTOM_IMAGES","SSBO","FADE_VARIABLE"),true);
            var configured = iris.configure(Map.of("TAA","true","VP_DANRP_PIPELINE","true"),macros);
            var post = iris.preprocess(configured,"world0/composite6.fsh",macros);
            require(post.errors().isEmpty() && post.text().contains("vpDanMotionTex"), "Early Eclipse preprocessing failed");
            Object nativePack = find("ShaderPacks", folder.toFile(), "Aimoh-Native-GF2");
            var read = nativePack.getClass().getDeclaredMethod("shader",String.class);
            read.setAccessible(true);
            require(new JSONObject((String)read.invoke(nativePack,"gf2/manifest.json")).has("original_camera_fill"),
                "Native manifest unavailable before first model preparation");
            require(viewpoint.platform.IrisPacks.active()==null, "R116 selected an optical pack");
            prepare();
            require(drawOrder(agent).equals(ORDER), "First model preparation has incorrect draw advice: "+drawOrder(agent));
            prepare();
            require(drawOrder(agent).equals(ORDER), "Model preparation applied duplicate advice");
            report.put("native_available_before_first_draw",true).put("eclipse_available_before_first_draw",true)
                .put("native_callback_order",ORDER).put("eclipse_taa_preprocess",true);
        }
        require(Files.readString(selection).equals(chosen) && Arrays.equals(Files.readAllBytes(custom),new byte[]{7,8,9}),
            "Player shader selection or custom pack was modified");
        Path project = Path.of(args[1]);
        ZomboidFileSystem.instance.setCacheDir(project.getParent().getParent().toString());
        ZomboidFileSystem.instance.init();
        var item = new zombie.core.znet.SteamWorkshopItem(project.toString());
        require(item.readWorkshopTxt() && item.validateContents()==null && item.getTitle().equals("少前2追放角色框架"),
            "Game Workshop validator rejected R116");
        report.put("workshop_validator_passed",true).put("saved_selection_preserved",true)
            .put("custom_pack_preserved",true).put("game_started",false);
        Files.writeString(root.resolve("startup.json"),report.toString(2));
        System.out.println(report);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
