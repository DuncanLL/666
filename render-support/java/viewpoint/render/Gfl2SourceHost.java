package viewpoint.render;
/** Stable optional character-core capability, backed only by the selected Native pack. */
public final class Gfl2SourceHost {
    public static String profileKey(int a,int b){Gfl2NativeRuntime.prepareData();return Gfl2NativeRegistry.active()?Gfl2NativeRegistry.profileKey(a,b):null;}
    public static String modelDescriptor(String key,int a,int b,int vertices,int materials){return Gfl2NativeRegistry.modelDescriptor(key,a,b,vertices,materials);}
    public static byte[] resourceBytes(String key,String path)throws java.io.IOException{return Gfl2NativeRegistry.resourceBytes(key,path);}
    private Gfl2SourceHost(){}
}
