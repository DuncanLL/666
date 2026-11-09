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
        return install(source,userRoot,"Aimoh-Eclipse-GF2.zip","managed-shader.properties");
    }
    public static Result installNative(Path source,Path userRoot)throws IOException{
        source=source.toAbsolutePath().normalize();userRoot=userRoot.toAbsolutePath().normalize();
        Path folder=userRoot.resolve("viewpoint-shaderpacks"),state=userRoot.resolve("aimoh-gf2");
        Path target=folder.resolve("Aimoh-Native-GF2");String wanted=sha(source),marker=".aimoh-managed.properties";
        if(Files.exists(target)){
            if(Files.isSymbolicLink(target)||!Files.isDirectory(target)||!Files.isRegularFile(target.resolve(marker)))return new Result(target,"USER_FILE_PRESERVED","");
            Properties old=new Properties();try(var stream=Files.newInputStream(target.resolve(marker))){old.load(stream);}
            Set<String> actual=new HashSet<>();
            try(var paths=Files.walk(target)){
                for(Path file:paths.toList()){
                    if(Files.isSymbolicLink(file))return new Result(target,"USER_FILE_PRESERVED","");
                    if(!Files.isRegularFile(file)||file.equals(target.resolve(marker)))continue;
                    String name=target.relativize(file).toString().replace('\\','/');actual.add("file."+name);
                    if(!sha(file).equals(old.getProperty("file."+name)))return new Result(target,"USER_FILE_PRESERVED","");
                }
            }
            Set<String> expected=new HashSet<>();for(String name:old.stringPropertyNames())if(name.startsWith("file."))expected.add(name);
            if(!actual.equals(expected))return new Result(target,"USER_FILE_PRESERVED","");
            if(wanted.equals(old.getProperty("archive.sha256")))return new Result(target,"CURRENT",wanted);
        }
        Files.createDirectories(folder);Files.createDirectories(state);
        Path staging=Files.createTempDirectory(state,"native-stage-");Properties next=new Properties();Set<String> names=new HashSet<>();
        try(ZipFile archive=new ZipFile(source.toFile())){
            if(archive.getEntry("pack.properties")==null||archive.getEntry("shaders/gf2/manifest.json")==null)throw new IOException("Native package layout is incomplete");
            for(ZipEntry entry:archive.stream().toList()){
                String name=entry.getName();Path file=staging.resolve(name).normalize();
                if(!file.startsWith(staging)||name.indexOf(':')>=0||name.indexOf('\\')>=0||!names.add(name)||name.equals(marker))throw new IOException("Invalid Native package path: "+name);
                if(entry.isDirectory()){Files.createDirectories(file);continue;}
                Files.createDirectories(file.getParent());try(var stream=archive.getInputStream(entry)){Files.copy(stream,file);}
                next.setProperty("file."+name,sha(file));
            }
        }
        next.setProperty("archive.sha256",wanted);
        try(var stream=Files.newOutputStream(staging.resolve(marker))){next.store(stream,"Managed Native folder; user modifications are preserved");}
        Path backup=null;
        if(Files.exists(target)){
            backup=state.resolve("native-before-"+UUID.randomUUID());
            if(!target.normalize().startsWith(folder)||!backup.normalize().startsWith(state))throw new IOException("Native update target is outside its root");
            Files.move(target,backup,StandardCopyOption.ATOMIC_MOVE);
        }
        try{Files.move(staging,target,StandardCopyOption.ATOMIC_MOVE);}
        catch(IOException failure){if(backup!=null&&!Files.exists(target))Files.move(backup,target,StandardCopyOption.ATOMIC_MOVE);throw failure;}
        return new Result(target,"INSTALLED",wanted);
    }
    private static Result install(Path source,Path userRoot,String filename,String stateName)throws IOException{
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
        Path target=folder.resolve(filename),record=stateFolder.resolve(stateName);
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
