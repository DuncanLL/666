package viewpoint.render;

import viewpoint.platform.IrisPacks;

/** Preserve the character-core entry while keeping resource keys owned by their backend. */
public final class AimohGf2SourceHost {
    public static String profileKey(int a,int b){
        if(IrisPacks.active()!=null)return AimohEclipseGf2SourceHost.profileKey(a,b);
        AimohNativeGf2Runtime.prepareData();
        return AimohNativeGf2Registry.active()?AimohNativeGf2Registry.profileKey(a,b):null;
    }
    public static String modelDescriptor(String key,int a,int b,int vertices,int materials){
        return key.startsWith("native:")?AimohNativeGf2Registry.modelDescriptor(key,a,b,vertices,materials)
            :AimohEclipseGf2SourceHost.modelDescriptor(key,a,b,vertices,materials);
    }
    public static byte[] resourceBytes(String key,String path)throws java.io.IOException{
        return key.startsWith("native:")?AimohNativeGf2Registry.resourceBytes(key,path):AimohEclipseGf2SourceHost.resourceBytes(key,path);
    }
    private AimohGf2SourceHost(){}
}
