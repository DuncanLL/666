package gfl2.render;

import java.nio.charset.StandardCharsets;

/** Bridges the manifest names in earlier sealed builds to the current resource layout. */
public final class ShaderManifestNames {
    public static byte[] bytes(byte[] raw,String path){
        if(raw==null||!manifest(path))return Gfl2SealedSources.bytes(raw,path);
        return text(new String(raw,StandardCharsets.UTF_8),path).getBytes(StandardCharsets.UTF_8);
    }

    public static String text(String raw,String path){
        String resolved=Gfl2SealedSources.text(raw,path);
        if(resolved==null||!manifest(path))return resolved;
        String previousCatalog=ManagedShaderPack.previousCatalogPrefix();
        if(resolved==raw){
            String legacy=raw.replace("GFL2Exilium","GFL2RenderSupport").replace("media/gfl2_rendering/",previousCatalog);
            if(!legacy.equals(raw))resolved=Gfl2SealedSources.text(legacy,path);
        }
        return resolved.replace("GFL2RenderSupport","GFL2Exilium").replace(previousCatalog,"media/gfl2_rendering/");
    }

    private static boolean manifest(String path){
        return path!=null&&path.replace('\\','/').endsWith("gf2/manifest.json");
    }

    private ShaderManifestNames(){}
}
