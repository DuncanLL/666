package aimoh.gf2.bridge;
import java.lang.instrument.*;
import java.security.ProtectionDomain;
import java.security.MessageDigest;
import java.nio.file.*;
import java.util.*;
import net.bytebuddy.jar.asm.*;
/** Native-only method-body integration; no Iris/Eclipse hooks or host class copies. */
public final class RuntimeHooks implements ClassFileTransformer {
    static final String R="viewpoint/render/";
    public static final Map<String,Integer> EXPECTED=Map.of(R+"WorldRenderer",2,R+"ModelPass",4,"aimoh/motion/PmxShading",2,"aimoh/motion/PmxOriginalInputs",1,"viewpoint/platform/PackOption",1);
    private volatile Throwable transformFailure;
    public static final String SUPPORTED_VP="c032ef0655f2c97c9eb4e3f713d087a718b0a13fdac786f3e64994420aaf37e8";
    private static final Set<String> SUPPORTED_HOSTS=Set.of(SUPPORTED_VP,"605476ba5f676fe1ac597d2e0adce49e45aac67be10e3c06defe9eaa32d4382f");
    public static void verifyHost()throws Exception{
        Set<String> copies=new LinkedHashSet<>();var entries=RuntimeHooks.class.getClassLoader().getResources("aimoh/gf2/bridge/Main.class");while(entries.hasMoreElements())copies.add(entries.nextElement().toExternalForm());
        if(copies.size()!=1)throw new IllegalStateException("More than one R116 render-support copy is on the Java classpath; keep one enabled version: "+copies);
        var resource=Class.forName("viewpoint.platform.GlProgram",false,RuntimeHooks.class.getClassLoader()).getResource("GlProgram.class");
        if(resource==null||!(resource.openConnection()instanceof java.net.JarURLConnection location))throw new IllegalStateException("Cannot identify official Viewpoint dependency JAR");
        verifyHost(Path.of(location.getJarFileURL().toURI()));
    }
    static void verifyHost(Path jar)throws Exception{
        String actual=Files.isRegularFile(jar)?HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))):"not-a-JAR";
        if(!SUPPORTED_HOSTS.contains(actual))throw new IllegalStateException("R116 Native test supports verified official Viewpoint 0.1.7-rc2 / rc3; current rc3 build "+SUPPORTED_VP+"; detected "+actual+". This VP update/build has not been validated; no runtime hooks applied.");
    }
    public static byte[] rewrite(String type,byte[] raw){
        if(!EXPECTED.containsKey(type))return null;
        if(type.equals("viewpoint/platform/PackOption")){
            ClassReader reader=new ClassReader(raw);ClassWriter writer=new ClassWriter(reader,ClassWriter.COMPUTE_MAXS);int[] hits={0};
            reader.accept(new ClassVisitor(Opcodes.ASM9,writer){
                public MethodVisitor visitMethod(int access,String name,String desc,String sig,String[] exceptions){
                    MethodVisitor base=super.visitMethod(access,name,desc,sig,exceptions);
                    if(!name.equals("declare")||!desc.equals("(Ljava/lang/String;Ljava/lang/String;)Lviewpoint/platform/LiveSettings$Setting;"))return base;
                    hits[0]++;return new MethodVisitor(Opcodes.ASM9,base){public void visitCode(){super.visitCode();mv.visitVarInsn(Opcodes.ALOAD,0);mv.visitVarInsn(Opcodes.ALOAD,2);mv.visitMethodInsn(Opcodes.INVOKESTATIC,"viewpoint/platform/AimohNativeCharacterMenu","section","(Lviewpoint/platform/PackOption;Ljava/lang/String;)Ljava/lang/String;",false);mv.visitVarInsn(Opcodes.ASTORE,2);}};
                }
            },0);
            if(hits[0]!=1)throw new IllegalStateException("Native character setting section entry changed");return writer.toByteArray();
        }
        if(!type.equals("aimoh/motion/PmxOriginalInputs"))return NativeHooks.rewrite(type,raw);
        ClassReader reader=new ClassReader(raw);ClassWriter writer=new ClassWriter(reader,0);int[] hits={0};
        reader.accept(new ClassVisitor(Opcodes.ASM9,writer){public MethodVisitor visitMethod(int access,String name,String desc,String sig,String[] ex){return new MethodVisitor(Opcodes.ASM9,super.visitMethod(access,name,desc,sig,ex)){public void visitLdcInsn(Object value){if("viewpoint.render.Gf2SourceHost".equals(value)){super.visitLdcInsn("viewpoint.render.AimohGf2SourceHost");hits[0]++;}else super.visitLdcInsn(value);}};}},0);
        if(hits[0]!=1)throw new IllegalStateException("Character source-host entry changed: "+hits[0]);return writer.toByteArray();
    }
    public void install(Instrumentation agent)throws Exception{
        verifyHost();
        Class<?> core=Class.forName("aimoh.motion.PmxShading",false,RuntimeHooks.class.getClassLoader());
        if(core.getDeclaredField("danrpVariant").getType()!=core||core.getDeclaredMethod("renderProfile").getReturnType()!=core||core.getDeclaredMethod("freezeDanrp",float[].class,float[].class).getReturnType()!=float[].class)throw new IllegalStateException("R116 character core GF2 ABI differs; no runtime hooks applied");
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
