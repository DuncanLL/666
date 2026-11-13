package aimoh.gf2.bridge;

import java.nio.charset.StandardCharsets;

/** Bridges the manifest names in earlier sealed builds to the current resource layout. */
public final class ShaderManifestNames {
    public static byte[] bytes(byte[] raw,String path){
        if(raw==null||!manifest(path))return AimohSealedSources.bytes(raw,path);
        return text(new String(raw,StandardCharsets.UTF_8),path).getBytes(StandardCharsets.UTF_8);
    }

    public static String text(String raw,String path){
        String resolved=AimohSealedSources.text(raw,path);
        if(resolved==null||!manifest(path))return resolved;
        if(resolved==raw){
            String legacy=raw.replace("GFL2Exilium","AimohGF2Eclipse").replace("media/gfl2_rendering/","media/aimoh_gf2/");
            if(!legacy.equals(raw))resolved=AimohSealedSources.text(legacy,path);
        }
        return resolved.replace("AimohGF2Eclipse","GFL2Exilium").replace("media/aimoh_gf2/","media/gfl2_rendering/");
    }

    private static boolean manifest(String path){
        return path!=null&&path.replace('\\','/').endsWith("gf2/manifest.json");
    }

    private ShaderManifestNames(){}
}
