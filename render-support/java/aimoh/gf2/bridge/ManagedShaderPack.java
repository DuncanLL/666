package aimoh.gf2.bridge;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Installs only our own ZIP, using the actual user's cache root. Never selects a shader. */
public final class ManagedShaderPack {
    public record Result(Path pack,String action,String sha256){}
    private static String sha(Path file)throws IOException{
        try(InputStream in=Files.newInputStream(file)){
            try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));}
            catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
        }
    }
    public static Result install(Path source,Path userRoot)throws IOException{
        source=source.toAbsolutePath().normalize();userRoot=userRoot.toAbsolutePath().normalize();
        if(!Files.isRegularFile(source))throw new FileNotFoundException("Bundled GF2 shader is missing");
        try(ZipFile zip=new ZipFile(source.toFile())){
            var names=zip.stream().map(ZipEntry::getName).filter(name->name.endsWith("shaders/gf2/manifest.json")).toList();
            if(names.size()!=1)throw new IOException("Bundled shader has no unique GF2 bootstrap");
            String bootstrap=new String(zip.getInputStream(zip.getEntry(names.get(0))).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            if(!bootstrap.contains("gf2-original-eclipse"))throw new IOException("Wrong bundled shader pipeline");
        }
        Path folder=userRoot.resolve("viewpoint-shaderpacks"),stateFolder=userRoot.resolve("aimoh-gf2");
        Files.createDirectories(folder);Files.createDirectories(stateFolder);
        Path target=folder.resolve("Aimoh-Eclipse-GF2.zip"),record=stateFolder.resolve("managed-shader.properties");
        String wanted=sha(source);Properties previous=new Properties();
        if(Files.exists(record))try(var stream=Files.newInputStream(record)){previous.load(stream);}
        if(Files.exists(target)){
            String current=sha(target);
            if(current.equals(wanted))return new Result(target,"CURRENT",current);
            // Do not overwrite a same-name user file which this MOD did not install.
            if(!current.equals(previous.getProperty("sha256")))return new Result(target,"USER_FILE_PRESERVED",current);
        }
        Path temporary=Files.createTempFile(folder,"Aimoh-Eclipse-GF2-",".tmp");
        try{
            Files.copy(source,temporary,StandardCopyOption.REPLACE_EXISTING);
            if(!sha(temporary).equals(wanted))throw new IOException("Shader staging checksum mismatch");
            Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        }finally{Files.deleteIfExists(temporary);}
        Properties next=new Properties();next.setProperty("sha256",wanted);next.setProperty("file",target.getFileName().toString());
        Path temporaryRecord=Files.createTempFile(stateFolder,"managed-shader-",".tmp");
        try{
            try(var stream=Files.newOutputStream(temporaryRecord)){next.store(stream,"Managed only by Aimoh GF2 Eclipse");}
            Files.move(temporaryRecord,record,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        }finally{Files.deleteIfExists(temporaryRecord);}
        return new Result(target,"INSTALLED",wanted);
    }
}
