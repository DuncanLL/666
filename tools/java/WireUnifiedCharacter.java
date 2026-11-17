import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import net.bytebuddy.jar.asm.*;

/** Wire the verified character entry points without recompiling its other classes. */
public final class WireUnifiedCharacter {
    private static final String STARTUP = "aimoh/gf2/bridge/UnifiedStartup";

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[1]);
        try (var source = new ZipFile(args[0])) {
            wire(source, output, "Main", "main", "([Ljava/lang/String;)V", "start");
            wire(source, output, "RenderPatch", "enter", "(Lviewpoint/render/SceneData;[Ljava/lang/Object;)V", "afterCharacterPatches");
        }
    }

    private static void wire(ZipFile source, Path output, String type, String method, String descriptor, String hook)
            throws Exception {
        String entry = "aimoh/pmxpreview/" + type + ".class";
        ClassReader reader;
        try (var data = source.getInputStream(source.getEntry(entry))) {
            reader = new ClassReader(data);
        }
        ClassWriter writer = new ClassWriter(reader, 0);
        int[] returns = {0};
        int[] existingHooks = {0};
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] errors) {
                MethodVisitor base = super.visitMethod(access, name, desc, signature, errors);
                if (!name.equals(method) || !desc.equals(descriptor)) return base;
                if ((access & Opcodes.ACC_STATIC) == 0) throw new IllegalArgumentException("Expected a static character entry");
                return new MethodVisitor(Opcodes.ASM9, base) {
                    @Override public void visitMethodInsn(int op, String owner, String called,
                                                          String desc, boolean isInterface) {
                        if (op == Opcodes.INVOKESTATIC && owner.equals(STARTUP)
                            && called.equals(hook) && desc.equals("()V")) {
                            existingHooks[0]++;
                        }
                        super.visitMethodInsn(op, owner, called, desc, isInterface);
                    }

                    @Override public void visitInsn(int op) {
                        if (op == Opcodes.RETURN) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, STARTUP, hook, "()V", false);
                            returns[0]++;
                        }
                        super.visitInsn(op);
                    }
                };
            }
        }, 0);
        if (returns[0] != 1) throw new IllegalArgumentException("Character entry has changed: " + type + "." + method);
        Path file = output.resolve(entry);
        Files.createDirectories(file.getParent());
        if (existingHooks[0] > 1) throw new IllegalArgumentException("Duplicate startup hook: " + type + "." + method);
        if (existingHooks[0] == 1) {
            try (var original = source.getInputStream(source.getEntry(entry))) {
                Files.write(file, original.readAllBytes());
            }
        } else {
            Files.write(file, writer.toByteArray());
        }
    }
}
