package aimoh.gf2.bridge;

import java.util.*;
import net.bytebuddy.jar.asm.*;

/** Native frame gates in memory. No official class or PMX core is packaged or written. */
public final class NativeHooks {
    private static final String R="viewpoint/render/",REG=R+"AimohNativeGf2Registry",RUN=R+"AimohNativeGf2Runtime",PMX="aimoh/motion/PmxShading";
    public static byte[] rewrite(String type,byte[] raw){
        if(!Set.of(R+"WorldRenderer",R+"ModelPass",PMX).contains(type))return raw;
        ClassReader reader=new ClassReader(raw);ClassWriter writer=new ClassWriter(reader,ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);int[] hits={0};
        reader.accept(new ClassVisitor(Opcodes.ASM9,writer){
            @Override public MethodVisitor visitMethod(int access,String name,String desc,String sig,String[] exceptions){
                MethodVisitor base=super.visitMethod(access,name,desc,sig,exceptions);
                boolean world=type.equals(R+"WorldRenderer"),profile=type.equals(PMX)&&name.equals("renderProfile")&&desc.equals("()L"+PMX+";");
                boolean freeze=type.equals(PMX)&&name.equals("freezeDanrp")&&desc.equals("([F[F)[F");
                boolean shown=type.equals(R+"ModelPass")&&name.equals("shown")&&desc.equals("(Lviewpoint/render/ModelDraws;I)Z");
                boolean gbuffer=type.equals(R+"ModelPass")&&name.equals("gbuffer")&&desc.equals("(Lviewpoint/render/FrameContext;)V");
                boolean caster=type.equals(R+"ModelPass")&&name.equals("shadow")&&desc.equals("(Lorg/joml/Matrix4f;[IILviewpoint/render/ModelBatches$Filter;)V");
                boolean shadowFilter=type.equals(R+"ModelPass")&&name.startsWith("lambda$shadow$")&&desc.equals("(Lviewpoint/render/ModelDraws;Lviewpoint/render/ModelBatches$Filter;I)Z");
                if(!world&&!profile&&!freeze&&!shown&&!gbuffer&&!caster&&!shadowFilter)return base;
                if(!world)hits[0]++;
                return new MethodVisitor(Opcodes.ASM9,base){
                    final Label nativeBody=new Label();boolean firstReturn=true;
                    void variant(){mv.visitVarInsn(Opcodes.ALOAD,0);mv.visitFieldInsn(Opcodes.GETFIELD,PMX,"danrpVariant","L"+PMX+";");}
                    void call(String owner,String method,String descriptor){mv.visitMethodInsn(Opcodes.INVOKESTATIC,owner,method,descriptor,false);}
                    @Override public void visitCode(){
                        super.visitCode();if(world||gbuffer||caster)return;Label old=new Label();
                        if(shown||shadowFilter){
                            mv.visitVarInsn(Opcodes.ALOAD,shadowFilter?0:1);mv.visitVarInsn(Opcodes.ILOAD,2);call(REG,"owns","(Lviewpoint/render/ModelDraws;I)Z");
                            mv.visitJumpInsn(Opcodes.IFEQ,old);mv.visitInsn(Opcodes.ICONST_0);mv.visitInsn(Opcodes.IRETURN);
                        }else if(freeze){
                            mv.visitVarInsn(Opcodes.ALOAD,0);call("aimoh/motion/PmxOriginalInputs","key","(L"+PMX+";)Ljava/lang/String;");mv.visitVarInsn(Opcodes.ASTORE,4);
                            mv.visitVarInsn(Opcodes.ALOAD,4);mv.visitJumpInsn(Opcodes.IFNULL,old);mv.visitVarInsn(Opcodes.ALOAD,4);mv.visitLdcInsn("native:");
                            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,"java/lang/String","startsWith","(Ljava/lang/String;)Z",false);mv.visitJumpInsn(Opcodes.IFEQ,old);
                            mv.visitInsn(Opcodes.ACONST_NULL);mv.visitVarInsn(Opcodes.ASTORE,3);mv.visitJumpInsn(Opcodes.GOTO,nativeBody);
                        }else{
                            call("viewpoint/platform/IrisPacks","active","()Lviewpoint/platform/IrisPacks$Active;");mv.visitJumpInsn(Opcodes.IFNONNULL,old);
                            variant();mv.visitJumpInsn(Opcodes.IFNULL,old);variant();call("aimoh/motion/PmxOriginalInputs","key","(L"+PMX+";)Ljava/lang/String;");mv.visitVarInsn(Opcodes.ASTORE,1);
                            mv.visitVarInsn(Opcodes.ALOAD,1);mv.visitJumpInsn(Opcodes.IFNULL,old);mv.visitVarInsn(Opcodes.ALOAD,1);mv.visitLdcInsn("native:");
                            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,"java/lang/String","startsWith","(Ljava/lang/String;)Z",false);mv.visitJumpInsn(Opcodes.IFEQ,old);variant();mv.visitInsn(Opcodes.ARETURN);
                        }
                        mv.visitLabel(old);
                    }
                    @Override public void visitInsn(int opcode){
                        if(gbuffer&&opcode==Opcodes.RETURN){mv.visitVarInsn(Opcodes.ALOAD,1);call(REG,"beforeLighting","(Lviewpoint/render/FrameContext;)V");}
                        if(caster&&opcode==Opcodes.RETURN){mv.visitVarInsn(Opcodes.ALOAD,1);mv.visitVarInsn(Opcodes.ALOAD,2);mv.visitVarInsn(Opcodes.ILOAD,3);mv.visitVarInsn(Opcodes.ALOAD,4);call(REG,"shadows","(Lorg/joml/Matrix4f;[IILviewpoint/render/ModelBatches$Filter;)V");}
                        super.visitInsn(opcode);
                        if(freeze&&firstReturn&&opcode==Opcodes.ARETURN){firstReturn=false;mv.visitLabel(nativeBody);}
                    }
                    @Override public void visitMethodInsn(int opcode,String owner,String method,String descriptor,boolean iface){
                        super.visitMethodInsn(opcode,owner,method,descriptor,iface);
                        if(world&&name.equals("begin")&&owner.equals(R+"LiveOptions")&&method.equals("frame")&&descriptor.equals("()V")){call(RUN,"prepareFrame","()V");hits[0]++;}
                        if(world&&name.equals("drawTranslucent")&&owner.equals(R+"AddonDraws")&&method.equals("afterOpaque")&&descriptor.equals("(Lviewpoint/render/FrameContext;)V")){
                            mv.visitFieldInsn(Opcodes.GETSTATIC,type,"frame","Lviewpoint/render/FrameContext;");call(RUN,"draw","(Lviewpoint/render/FrameContext;)V");hits[0]++;
                        }
                    }
                };
            }
        },ClassReader.EXPAND_FRAMES);
        int expected=type.equals(R+"ModelPass")?4:2;
        if(hits[0]!=expected)throw new IllegalStateException(type+": Native integration expected "+expected+", found "+hits[0]);
        return writer.toByteArray();
    }
}
