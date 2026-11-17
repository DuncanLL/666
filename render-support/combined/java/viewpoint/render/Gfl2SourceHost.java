package viewpoint.render;

import viewpoint.platform.IrisPacks;

/** Preserve the character-core entry while keeping resource keys owned by their backend. */
public final class Gfl2SourceHost {
    public static String profileKey(int a,int b){
        if(IrisPacks.active()!=null)return Gfl2EclipseSourceHost.profileKey(a,b);
        Gfl2NativeRuntime.prepareData();
        return Gfl2NativeRegistry.active()?Gfl2NativeRegistry.profileKey(a,b):null;
    }
    public static String modelDescriptor(String key,int a,int b,int vertices,int materials){
        return key.startsWith("native:")?Gfl2NativeRegistry.modelDescriptor(key,a,b,vertices,materials)
            :Gfl2EclipseSourceHost.modelDescriptor(key,a,b,vertices,materials);
    }
    public static byte[] resourceBytes(String key,String path)throws java.io.IOException{
        return key.startsWith("native:")?Gfl2NativeRegistry.resourceBytes(key,path):Gfl2EclipseSourceHost.resourceBytes(key,path);
    }
    private Gfl2SourceHost(){}
}
