package viewpoint.render;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import viewpoint.platform.GlProgram;

/** Model and uniform operations provided by the verified Workshop renderer. */
final class AimohWorkshopRender {
    private static final Field DRAWS;
    private static final Method BEGIN, END, UNIFORMS;
    static {
        try {
            DRAWS=ModelPass.class.getDeclaredField("draws");
            BEGIN=ModelPass.class.getDeclaredMethod("begin");
            END=ModelPass.class.getDeclaredMethod("end");
            UNIFORMS=FrameUniforms.class.getDeclaredMethod("values",GlProgram.class,FrameContext.class);
            DRAWS.setAccessible(true);
            for(Method method:new Method[]{BEGIN,END,UNIFORMS})method.setAccessible(true);
        } catch(ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    static ModelDraws draws(ModelPass models) {
        try { return (ModelDraws)DRAWS.get(models); }
        catch(IllegalAccessException failure) { throw new IllegalStateException(failure); }
    }

    interface Bindings extends AutoCloseable {
        @Override void close();
    }

    static Bindings modelBindings(ModelPass models) {
        invoke(BEGIN,models);
        return ()->invoke(END,models);
    }

    static void uniforms(GlProgram program,FrameContext frame) {
        program.use();
        invoke(UNIFORMS,null,program,frame);
    }

    private static void invoke(Method method,Object target,Object... args) {
        try { method.invoke(target,args); }
        catch(InvocationTargetException failure) {
            if(failure.getCause() instanceof Error error)throw error;
            if(failure.getCause() instanceof RuntimeException exception)throw exception;
            throw new IllegalStateException(failure.getCause());
        } catch(IllegalAccessException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
