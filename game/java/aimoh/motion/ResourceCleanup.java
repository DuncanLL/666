package aimoh.motion;

/** Release every owned resource, preserving the first failure and any later ones. */
public final class ResourceCleanup {
    private ResourceCleanup() {}
    public static void close(AutoCloseable... resources) {
        Throwable failure=null;
        for(AutoCloseable resource:resources)if(resource!=null){
            try{resource.close();}
            catch(Throwable e){if(failure==null)failure=e;else if(failure!=e)failure.addSuppressed(e);}
        }
        if(failure instanceof Error e)throw e;
        if(failure instanceof RuntimeException e)throw e;
        if(failure!=null)throw new IllegalStateException("Resource cleanup failed",failure);
    }
}
