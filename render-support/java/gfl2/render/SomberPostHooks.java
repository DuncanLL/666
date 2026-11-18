package gfl2.render;
import net.bytebuddy.jar.asm.*;
/** Only the existing supported host's two post-input boundaries change. */
public final class SomberPostHooks {
    public static final String POST = "viewpoint/render/PostPass";
    public static byte[] adapt(byte[] bytes){
        ClassReader in = new ClassReader(bytes);
        ClassWriter out = new ClassWriter(in,ClassWriter.COMPUTE_MAXS);
        int[] found={0,0};
        in.accept(new ClassVisitor(Opcodes.ASM9,out){
            @Override public MethodVisitor visitMethod(int acc,String name,String desc,String sig,String[] ex){
                var original=super.visitMethod(acc,name,desc,sig,ex);
                boolean bind=name.equals("inputs") && desc.equals("(Lviewpoint/render/FrameContext;)V");
                if(!bind && !(name.equals("release") && desc.equals("()V"))) return original;
                return new MethodVisitor(Opcodes.ASM9,original){
                    @Override public void visitInsn(int opcode){
                        if(opcode==Opcodes.RETURN){
                            found[bind?0:1]++;
                            if(bind) mv.visitVarInsn(Opcodes.ALOAD,1);
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC,"viewpoint/render/Gfl2SomberCoverage",
                                bind?"bind":"release",desc,false);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        },0);
        if(found[0]!=1 || found[1]!=1) throw new IllegalStateException("Somber post input/release changed");
        return out.toByteArray();
    }
}