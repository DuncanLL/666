import java.lang.instrument.*;
import java.security.ProtectionDomain;
import net.bytebuddy.jar.asm.*;

/** Counts Java GL query call sites on the measured render thread. Time normal frames separately from the audit. */
public final class FrameQueryCounter {
    private static int count;
    public static int sites,visited;
    private static boolean countFinite;
    private static int finiteTests;
    public static void beginFinite(){finiteTests=0;countFinite=true;}
    public static void finite(){if(countFinite)finiteTests++;}
    public static int endFinite(){countFinite=false;return finiteTests;}
    private static Thread measured;
    private static long time;
    public static void tick(long frame){time=1_000_000_000L+frame*16_666_667L;}
    public static long frameTime(){return time;}
    public static void fixedClock() throws ReflectiveOperationException {
        var clock=Class.forName("viewpoint.render.WorldRenderer").getDeclaredField("clock");
        clock.setAccessible(true);
        clock.set(null,(java.util.function.LongSupplier)FrameQueryCounter::frameTime);
    }
    public static void start(){count=0;measured=Thread.currentThread();}
    public static int stop(){measured=null;return count;}
    public static void query(){if(Thread.currentThread()==measured)count++;}
    private static Instrumentation instrument;
    private static volatile Throwable transformError;
    public static void refresh() throws Exception {
        transformError=null;
        for(Class<?> c:instrument.getAllLoadedClasses()){
            String n=c.getName();
            if (!n.startsWith("viewpoint.") && !n.startsWith("gfl2.") && !n.startsWith("zombie.")) continue;
            if(c.isArray() || !instrument.isModifiableClass(c))continue;
            try(var in=c.getResourceAsStream("/"+n.replace('.','/')+".class")){
                if(in==null)continue;
                String pool=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.ISO_8859_1);
                if(pool.contains("org/lwjgl/opengl/GL")&&(pool.contains("glGet")||pool.contains("glIsEnabled")))instrument.retransformClasses(c);
            }
        }
        if(transformError!=null)throw new IllegalStateException("Incomplete GL trace",transformError);
        System.out.println("GL sites="+sites+" classes="+visited);
    }
    public static void premain(String args, Instrumentation inst){
        instrument=inst; try { inst.appendToSystemClassLoaderSearch(new java.util.jar.JarFile(args)); } catch(java.io.IOException ex){throw new IllegalStateException(ex);} System.out.println("PERF AGENT START"); inst.addTransformer(new ClassFileTransformer(){
            @Override public byte[] transform(ClassLoader l,String name,Class<?> old,ProtectionDomain d,byte[] bytes){
                if(name==null|| !(name.startsWith("viewpoint/")||name.startsWith("gfl2/")||name.startsWith("zombie/")))return null;
                visited++; try { var reader=new ClassReader(bytes);var writer=new ClassWriter(reader,ClassWriter.COMPUTE_MAXS);
                reader.accept(new ClassVisitor(Opcodes.ASM9,writer){
                    @Override public MethodVisitor visitMethod(int a,String n,String desc,String sig,String[] ex){
                        return new MethodVisitor(Opcodes.ASM9,super.visitMethod(a,n,desc,sig,ex)){
                            @Override public void visitMethodInsn(int op,String owner,String method,String type,boolean iface){
                                if(name.equals("gfl2/motion/PmxDrawMesh$FrameData") && owner.equals("java/lang/Float")
                                        && method.equals("isFinite") && type.equals("(F)Z")){
                                    mv.visitMethodInsn(Opcodes.INVOKESTATIC,"FrameQueryCounter","finite","()V",false);
                                }
                                if(owner.startsWith("org/lwjgl/opengl/GL")&&(method.startsWith("glGet")||method.startsWith("glIsEnabled"))){
                                    sites++;mv.visitMethodInsn(Opcodes.INVOKESTATIC,"FrameQueryCounter","query","()V",false);
                                }
                                if(owner.equals("java/lang/System") && method.equals("nanoTime") && ((n.equals("begin") && name.equals("viewpoint/render/WorldRenderer")) ||
                                        (n.equals("draw") && name.equals("viewpoint/render/NativeDefaultSceneFixture")))){
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,"FrameQueryCounter","frameTime","()J",false);
                                    return;
                                }
                                super.visitMethodInsn(op,owner,method,type,iface);
                            }
                        };
                    }
                },0);return writer.toByteArray(); } catch(RuntimeException | LinkageError failure){transformError=failure;throw failure;}
            }
        },true);
    }
}
