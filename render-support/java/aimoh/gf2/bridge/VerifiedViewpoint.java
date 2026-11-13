package aimoh.gf2.bridge;

import java.net.JarURLConnection;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Both distributions target the same unmodified official host. */
final class VerifiedViewpoint {
    static final String SHA256="d1c95a9af369aba499fe3d7fe721275bc928c944bd8288ed2da58af3ea801e32";

    static void verify()throws Exception{
        ClassLoader loader=VerifiedViewpoint.class.getClassLoader();
        Set<String> copies=new LinkedHashSet<>();
        var entries=loader.getResources("aimoh/gf2/bridge/Main.class");
        while(entries.hasMoreElements())copies.add(entries.nextElement().toExternalForm());
        if(copies.size()!=1)throw new IllegalStateException("Keep exactly one R116 render support distribution enabled: "+copies);
        var resource=Class.forName("viewpoint.platform.GlProgram",false,loader).getResource("GlProgram.class");
        if(resource==null||!(resource.openConnection() instanceof JarURLConnection location))
            throw new IllegalStateException("Cannot identify official Viewpoint dependency JAR");
        Path jar=Path.of(location.getJarFileURL().toURI());
        String actual=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        if(!SHA256.equals(actual))
            throw new IllegalStateException("R116 requires official Viewpoint 0.1.7-rc4. Detected JAR SHA-256: "+actual);
    }

    private VerifiedViewpoint(){}
}
