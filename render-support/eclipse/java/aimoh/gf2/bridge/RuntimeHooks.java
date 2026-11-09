package aimoh.gf2.bridge;

import java.lang.instrument.*;
import java.security.ProtectionDomain;
import java.util.*;
import net.bytebuddy.jar.asm.*;

/** Method bodies only. No native class is packaged, renamed, or given new members. */
public final class RuntimeHooks implements ClassFileTransformer {
    static final String R="viewpoint/render/",D=R+"AimohGF2Services",S=R+"AimohGf2SourceHost",C=R+"AimohGf2AddonContext";
    static final String LINK="(Lviewpoint/iris/IrisPack;Lviewpoint/iris/IrisPack$Configured;Ljava/util/Map;)Lviewpoint/render/IrisPrograms;";
    public static final Map<String,Integer> EXPECTED=Map.of(R+"IrisProgram",4,R+"IrisBindings",2,R+"IrisPipeline",3,
        R+"IrisPrograms",3,R+"IrisFrame",4,R+"ModelPass",2,R+"IrisGeometry",1,"aimoh/motion/PmxOriginalInputs",1,"viewpoint/platform/ShaderLibrary",2);
    private volatile Throwable transformFailure;
    private static String t(String name){return "L"+R+name+";";}
    public static byte[] rewrite(String type,byte[] raw){
        Integer expected=EXPECTED.get(type);if(expected==null)return null;
        ClassReader reader=new ClassReader(raw);ClassWriter writer=new ClassWriter(reader,ClassWriter.COMPUTE_MAXS);int[] hits={0};
        reader.accept(new ClassVisitor(Opcodes.ASM9,writer){
            @Override public MethodVisitor visitMethod(int access,String name,String desc,String sig,String[] ex){
                return new MethodVisitor(Opcodes.ASM9,super.visitMethod(access,name,desc,sig,ex)){
                    final boolean linking=type.equals(R+"IrisPrograms")&&name.equals("link")&&desc.equals(LINK);
                    final Label start=new Label(),end=new Label(),failed=new Label();boolean pool=false,early=false;
                    void load(int... slots){for(int n:slots)mv.visitVarInsn(Opcodes.ALOAD,n);}
                    void invoke(String owner,String method,String descriptor){mv.visitMethodInsn(Opcodes.INVOKESTATIC,owner,method,descriptor,false);}
                    void call(String owner,String method,String descriptor){invoke(owner,method,descriptor);hits[0]++;}
                    void field(String owner,String field,String descriptor){load(0);mv.visitFieldInsn(Opcodes.GETFIELD,owner,field,descriptor);}
                    @Override public void visitCode(){
                        super.visitCode();
                        if(linking){load(0);invoke(C,"beginCompile","(Lviewpoint/iris/IrisPack;)V");mv.visitLabel(start);}
                        if(type.equals(R+"IrisPrograms")&&name.equals("delete")){load(0);call(D,"releasePrograms","("+t("IrisPrograms")+")V");}
                        if(type.equals(R+"IrisPipeline")){
                            if(name.equals("release")){load(0);call(D,"release","("+t("IrisPipeline")+")V");}
                            if(name.equals("create")&&desc.equals("(Lviewpoint/iris/IrisPack;Ljava/util/Map;Ljava/util/Map;)"+t("IrisPipeline"))){load(0,2);call(C,"macros","(Lviewpoint/iris/IrisPack;Ljava/util/Map;)Ljava/util/Map;");mv.visitVarInsn(Opcodes.ASTORE,2);}
                        }
                        if(type.equals(R+"IrisBindings")&&name.equals("texture")&&desc.equals("(Ljava/lang/String;)I")){
                            field(type,"targets",t("IrisTargets"));load(1);call(C,"texture","("+t("IrisTargets")+"Ljava/lang/String;)I");
                            mv.visitInsn(Opcodes.DUP);Label nativeBody=new Label();mv.visitJumpInsn(Opcodes.IFLT,nativeBody);mv.visitInsn(Opcodes.IRETURN);
                            mv.visitLabel(nativeBody);mv.visitFrame(Opcodes.F_SAME1,0,null,1,new Object[]{Opcodes.INTEGER});mv.visitInsn(Opcodes.POP);
                        }
                        if(type.equals(R+"IrisFrame"))switch(name){
                            case "draw"->{load(1,2);call(D,"beginFrame","("+t("IrisPipeline")+t("FrameContext")+")V");}
                            case "opaque"->{load(1,2,3);field(type,"models",t("ModelPass"));field(type,"values","Ljava/util/Map;");call(D,"prepare","("+t("IrisPipeline")+t("FrameContext")+t("ShadowPass")+t("ModelPass")+"Ljava/util/Map;)V");}
                            case "computes"->{load(0,1,2,3,4,5);call(S,"beforeSourcePass","("+t("IrisFrame")+t("IrisPipeline")+t("IrisPrograms$Pass")+"Ljava/lang/String;"+t("FrameContext")+"Ljava/util/Set;)V");}
                        }
                    }
                    @Override public void visitInsn(int op){
                        if(type.equals("viewpoint/platform/ShaderLibrary")&&name.equals("read")&&op==Opcodes.ARETURN){load(0);call(C,"shaderSource","(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");}
                        if(linking&&op==Opcodes.ARETURN){mv.visitInsn(Opcodes.DUP);load(0,1,2);call(D,"link","("+t("IrisPrograms")+"Lviewpoint/iris/IrisPack;Lviewpoint/iris/IrisPack$Configured;Ljava/util/Map;)V");invoke(C,"endCompile","()V");}
                        if(op==Opcodes.RETURN){
                            if(type.equals(R+"IrisBindings")&&name.equals("bind")){field(type,"targets",t("IrisTargets"));load(1);call(C,"nativeLights","("+t("IrisTargets")+t("IrisProgram")+")V");}
                            if(type.equals(R+"ModelPass")&&name.equals("begin"))call(R+"AimohGf2IrisGlass","modelState","()V");
                        }
                        super.visitInsn(op);
                    }
                    @Override public void visitMaxs(int stack,int locals){
                        if(linking){
                            mv.visitLabel(end);mv.visitTryCatchBlock(start,end,failed,null);mv.visitLabel(failed);
                            mv.visitFrame(Opcodes.F_FULL,3,new Object[]{"viewpoint/iris/IrisPack","viewpoint/iris/IrisPack$Configured","java/util/Map"},1,new Object[]{"java/lang/Throwable"});
                            invoke(C,"endCompile","()V");mv.visitInsn(Opcodes.ATHROW);
                        }
                        super.visitMaxs(stack,locals);
                    }
                    @Override public void visitVarInsn(int op,int var){
                        if(type.equals(R+"IrisPipeline")&&name.equals("checkPool")&&op==Opcodes.LSTORE&&!pool){load(0);call(D,"extraBytes","("+t("IrisPipeline")+")J");mv.visitInsn(Opcodes.LADD);pool=true;}
                        super.visitVarInsn(op,var);
                        if(type.equals(R+"IrisProgram")&&name.equals("<init>")&&op==Opcodes.ILOAD&&var==6){
                            load(1);call(D,"checkedUserUnit","(ILjava/lang/String;)I");
                        }
                    }
                    @Override public void visitIntInsn(int op,int value){if(type.equals(R+"IrisProgram")&&name.equals("<init>")&&op==Opcodes.BIPUSH&&value==96){load(1,2);call(C,"firstUserUnit","(Ljava/lang/String;Lviewpoint/platform/GlProgram;)I");}else super.visitIntInsn(op,value);}
                    @Override public void visitIincInsn(int var,int amount){super.visitIincInsn(var,amount);if(type.equals(R+"IrisProgram")&&name.equals("<init>")&&var==6&&amount==1){load(1);mv.visitVarInsn(Opcodes.ILOAD,var);call(C,"nextUserUnit","(Ljava/lang/String;I)I");mv.visitVarInsn(Opcodes.ISTORE,var);}}
                    @Override public void visitLdcInsn(Object value){
                        if(type.equals("aimoh/motion/PmxOriginalInputs")&&"viewpoint.render.Gf2SourceHost".equals(value)){super.visitLdcInsn("viewpoint.render.AimohGf2SourceHost");hits[0]++;}else super.visitLdcInsn(value);
                    }
                    @Override public void visitMethodInsn(int op,String owner,String method,String descriptor,boolean itf){
                        if(type.equals(R+"IrisFrame")&&name.equals("opaque")&&owner.equals(R+"IrisTargets")&&method.equals("copyDepth")&&!early){load(0,1,2);call(S,"sourceEarly","("+t("IrisFrame")+t("IrisPipeline")+t("FrameContext")+")V");early=true;}
                        if(type.equals(R+"ModelPass")&&name.equals("iris")&&owner.equals(R+"ModelBatches")&&method.equals("draw")){mv.visitInsn(Opcodes.SWAP);call(D,"filter","("+t("ModelBatches$Filter")+")"+t("ModelBatches$Filter"));mv.visitInsn(Opcodes.SWAP);}
                        super.visitMethodInsn(op,owner,method,descriptor,itf);
                        if(type.equals(R+"IrisGeometry")&&name.equals("viewpoint")&&owner.equals(R+"SurfacePass")&&method.equals("torches")){load(1,3);call(C,"bindMuzzle","("+t("IrisProgram")+t("ShadowPass")+")V");}
                        if(type.equals(R+"IrisPrograms")&&name.equals("geometry")&&owner.equals("viewpoint/iris/IrisAssembler")&&method.equals("fragment")){load(2);mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,"viewpoint/iris/ProgramSet$Program","name","()Ljava/lang/String;",false);call(C,"fragment","(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");}
                    }
                };
            }
        },0);
        if(hits[0]!=expected)throw new IllegalStateException(type+": expected "+expected+" integration points, found "+hits[0]);
        return writer.toByteArray();
    }
    public void install(Instrumentation agent)throws Exception{
        VerifiedViewpoint.verify();
        if(!agent.isRetransformClassesSupported())throw new IllegalStateException("ZombieBuddy retransformation unavailable");
        ClassLoader loader=RuntimeHooks.class.getClassLoader();
        if(loader.getResource(R+"Gf2SourceHost.class")!=null)throw new IllegalStateException("A private GF2 host is already installed; refusing double rendering");
        var loaded=new ArrayList<Class<?>>();
        for(String name:EXPECTED.keySet())try(var input=loader.getResourceAsStream(name+".class")){
            if(input==null)throw new IllegalStateException("Required integration class missing: "+name);
            rewrite(name,input.readAllBytes());
            Class<?> type=Class.forName(name.replace('/','.'),false,loader);
            if(!agent.isModifiableClass(type))throw new IllegalStateException("Class cannot be integrated: "+name);
            loaded.add(type);
        }
        agent.addTransformer(this,true);
        try{
            agent.retransformClasses(loaded.toArray(Class<?>[]::new));
            if(transformFailure!=null)throw new IllegalStateException("Runtime integration rejected",transformFailure);
        }catch(Throwable failure){
            agent.removeTransformer(this);
            var restore=new ArrayList<Class<?>>();for(Class<?> type:agent.getAllLoadedClasses())if(EXPECTED.containsKey(type.getName().replace('.','/'))&&agent.isModifiableClass(type))restore.add(type);
            if(!restore.isEmpty())agent.retransformClasses(restore.toArray(Class<?>[]::new));
            throw failure;
        }
    }
    @Override public byte[] transform(ClassLoader loader,String name,Class<?> type,ProtectionDomain domain,byte[] bytes)throws IllegalClassFormatException{
        if(!EXPECTED.containsKey(name))return null;
        try{return rewrite(name,bytes);}catch(Throwable failure){transformFailure=failure;System.err.println("[AimohGF2] Integration failed for "+name+": "+failure);throw new IllegalClassFormatException(failure.toString());}
    }
}
