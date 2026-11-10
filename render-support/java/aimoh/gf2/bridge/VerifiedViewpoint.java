package aimoh.gf2.bridge;

import java.net.JarURLConnection;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Both distributions target the same unmodified official host. */
final class VerifiedViewpoint {
    static final String SHA256="67f83b05a27c4112ce9edc596da7c491d51e9015862dccafa8495f0b9352625e";

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
            throw new IllegalStateException("R116 requires official Workshop Viewpoint 0.1.6.2. Detected JAR SHA-256: "+actual);
    }

    private VerifiedViewpoint(){}
}
