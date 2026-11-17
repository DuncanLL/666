import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import net.bytebuddy.jar.asm.*;

/** Moves the two optional Eclipse shader adjustments out of the release class constant pool. */
public final class SealEclipseCalibration {
    private static final String ADJUSTMENT = "(Ljava/lang/String;F)Ljava/lang/String;";
    private record Calibration(String name, String text) {}

    private static String calibrationName(String text) {
        if (text.contains("const float keyStrength=%s;") && text.contains("float shoulder=")) return "key";
        if (text.contains("const float highStrength=%s;") && text.contains("float missingDiffuse=")) return "shadow";
        return null;
    }

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[2]);
        String loader = args[3];
        ClassReader reader = new ClassReader(Files.readAllBytes(Path.of(args[0])));
        Map<String, Calibration> methods = new LinkedHashMap<>();
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exceptions) {
                if ((access & Opcodes.ACC_STATIC) == 0 || !desc.equals(ADJUSTMENT)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitLdcInsn(Object value) {
                        if (value instanceof String text) {
                            String id = calibrationName(text);
                            if (id != null && methods.putIfAbsent(name, new Calibration(id, text)) != null)
                                throw new IllegalArgumentException("Duplicate Eclipse calibration in " + name);
                        }
                    }
                };
            }
        }, 0);
        if (methods.size() != 2 || methods.values().stream().map(Calibration::name).distinct().count() != 2)
            throw new IllegalArgumentException("Expected one key and one shadow calibration");

        // Use a fresh constant pool so unreferenced shader text is discarded.
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exceptions) {
                MethodVisitor method = super.visitMethod(access, name, desc, sig, exceptions);
                Calibration entry = methods.get(name);
                if (entry == null || !desc.equals(ADJUSTMENT)) return method;
                return new MethodVisitor(Opcodes.ASM9, method) {
                    @Override public void visitCode() {
                        super.visitCode();
                        Label apply = new Label();
                        mv.visitVarInsn(Opcodes.ALOAD, 0);
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, loader, "hasCalibration", "(Ljava/lang/String;)Z", false);
                        mv.visitJumpInsn(Opcodes.IFNE, apply);
                        mv.visitVarInsn(Opcodes.ALOAD, 0);
                        mv.visitInsn(Opcodes.ARETURN);
                        mv.visitLabel(apply);
                        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                    }

                    @Override public void visitLdcInsn(Object value) {
                        if (!entry.text().equals(value)) {
                            super.visitLdcInsn(value);
                            return;
                        }
                        mv.visitLdcInsn(entry.name());
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, loader, "calibration",
                            "(Ljava/lang/String;)Ljava/lang/String;", false);
                    }
                };
            }
        }, 0);
        Files.write(Path.of(args[1]), writer.toByteArray());
        for (Calibration entry : methods.values()) Files.writeString(directory.resolve(entry.name()), entry.text());
    }
}
