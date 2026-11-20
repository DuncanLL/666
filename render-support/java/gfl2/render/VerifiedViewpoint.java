package gfl2.render;

import java.net.JarURLConnection;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Verifies Viewpoint is loaded and no duplicate GFL2 render distribution is active. */
final class VerifiedViewpoint {
    static final String SHA256="8d0d5a73227d3ba0724cab076f621435ebaed8ed2c73bfaf7d32a90b0a249e1f";
    private static volatile boolean loggedOtherHost;

    static void verify()throws Exception{
        ClassLoader loader=VerifiedViewpoint.class.getClassLoader();
        Set<String> copies=new LinkedHashSet<>();
        var entries=loader.getResources("gfl2/render/Main.class");
        while(entries.hasMoreElements())copies.add(entries.nextElement().toExternalForm());
        if(copies.size()!=1)throw new IllegalStateException("Keep exactly one GFL2 render support distribution enabled: "+copies);
        var resource=Class.forName("viewpoint.platform.GlProgram",false,loader).getResource("GlProgram.class");
        if(resource==null)throw new IllegalStateException("Cannot identify Viewpoint dependency");
        if(!loggedOtherHost && resource.openConnection() instanceof JarURLConnection location){
            try{
                Path jar=Path.of(location.getJarFileURL().toURI());
                String actual=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
                if(!SHA256.equals(actual)){
                    loggedOtherHost=true;
                    System.out.println("[GFL2] Viewpoint JAR differs from 0.1.7 baseline ("+actual.substring(0,12)+"); proceeding with bytecode hook verification.");
                }
            }catch(Exception ignored){}
        }
    }

    private VerifiedViewpoint(){}
}
