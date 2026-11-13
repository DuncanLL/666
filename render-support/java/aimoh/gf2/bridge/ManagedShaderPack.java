package aimoh.gf2.bridge;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Installs owned packs and carries forward their existing Viewpoint selection. */
public final class ManagedShaderPack {
    public static final String NATIVE="Native-GFL2";
    public static final String ECLIPSE="Eclipse GFL 2";
    private static final String LEGACY_NATIVE="Aimoh-Native-GF2";
    private static final String LEGACY_ECLIPSE="Aimoh-Eclipse-GF2";
    public record Result(Path pack,String action,String sha256){}
    private static String sha(Path file)throws IOException{
        try(InputStream in=Files.newInputStream(file)){
            try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));}
            catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
        }
    }
    public static Result install(Path source,Path userRoot)throws IOException{
        return install(source,userRoot,ECLIPSE+".zip","managed-shader.properties");
    }
    public static Result installNative(Path source,Path userRoot)throws IOException{
        source=source.toAbsolutePath().normalize();userRoot=userRoot.toAbsolutePath().normalize();
        Path folder=userRoot.resolve("viewpoint-shaderpacks"),state=userRoot.resolve("gfl2-rendering");
        Path target=folder.resolve(NATIVE);String wanted=sha(source),marker=".gfl2-managed.properties";
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
        Path folder=userRoot.resolve("viewpoint-shaderpacks"),stateFolder=userRoot.resolve("gfl2-rendering");
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
        Path temporary=Files.createTempFile(folder,"gfl2-eclipse-",".tmp");
        try{
            Files.copy(source,temporary,StandardCopyOption.REPLACE_EXISTING);
            if(!sha(temporary).equals(wanted))throw new IOException("Shader staging checksum mismatch");
            Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        }finally{Files.deleteIfExists(temporary);}
        Properties next=new Properties();next.setProperty("sha256",wanted);next.setProperty("file",target.getFileName().toString());
        Path temporaryRecord=Files.createTempFile(stateFolder,"managed-shader-",".tmp");
        try{
            try(var stream=Files.newOutputStream(temporaryRecord)){next.store(stream,"Managed GFL2 Eclipse shader");}
            Files.move(temporaryRecord,record,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        }finally{Files.deleteIfExists(temporaryRecord);}
        return new Result(target,"INSTALLED",wanted);
    }

    public static void migrateSelection(Path user,Result installed,boolean eclipse)throws IOException{
        if(installed.action().equals("USER_FILE_PRESERVED"))return;
        String old=eclipse?LEGACY_ECLIPSE:LEGACY_NATIVE;
        String current=eclipse?"Eclipse_GFL_2":NATIVE;
        String oldId=eclipse?"minecraft-"+old:old;
        String newId=eclipse?"minecraft-"+current:current;
        Path legacy=user.resolve("viewpoint-shaderpacks").resolve(old+(eclipse?".zip":""));
        boolean hasLegacy=Files.exists(legacy);
        if(hasLegacy&&!managedLegacy(user,legacy,eclipse))return;
        for(String name:List.of("viewpoint.properties","viewpoint-live.properties")){
            Path path=user.resolve(name);
            if(!Files.isRegularFile(path))continue;
            List<String> lines=Files.readAllLines(path),updated=new ArrayList<>();
            Set<String> keys=new HashSet<>();
            for(String line:lines){
                int at=line.indexOf('=');
                if(at>0)keys.add(line.substring(0,at));
            }
            String before=(eclipse?"minecraft.":"pack.")+oldId+".";
            String after=(eclipse?"minecraft.":"pack.")+newId+".";
            for(String line:lines){
                if(line.equals("graphics.pack="+oldId))line="graphics.pack="+newId;
                else if(line.startsWith(before)){
                    int at=line.indexOf('=');
                    if(at>0){
                        String key=after+line.substring(before.length(),at);
                        if(!keys.add(key))continue;
                        line=key+line.substring(at);
                    }
                }
                updated.add(line);
            }
            if(!updated.equals(lines)){
                Path temp=Files.createTempFile(user,"gfl2-viewpoint-",".tmp");
                try{
                    Files.write(temp,updated);
                    Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
                }finally{Files.deleteIfExists(temp);}
            }
        }
        if(!hasLegacy)return;
        Path backup=Files.createTempDirectory(user.resolve("gfl2-rendering"),"previous-pack-");
        Files.move(legacy,backup.resolve((eclipse?"Eclipse.zip":"Native")),StandardCopyOption.ATOMIC_MOVE);
    }

    private static boolean managedLegacy(Path user,Path legacy,boolean eclipse)throws IOException{
        if(Files.isSymbolicLink(legacy))return false;
        if(eclipse){
            Path state=user.resolve("aimoh-gf2/managed-shader.properties");
            if(!Files.isRegularFile(legacy)||!Files.isRegularFile(state))return false;
            Properties record=new Properties();
            try(var in=Files.newInputStream(state)){record.load(in);}
            return legacy.getFileName().toString().equals(record.getProperty("file"))&&sha(legacy).equals(record.getProperty("sha256"));
        }else{
            Path marker=legacy.resolve(".aimoh-managed.properties");
            if(!Files.isRegularFile(marker))return false;
            Properties record=new Properties();
            try(var in=Files.newInputStream(marker)){record.load(in);}
            Set<String> actual=new HashSet<>();
            try(var paths=Files.walk(legacy)){
                for(Path file:paths.toList()){
                    if(Files.isSymbolicLink(file))return false;
                    if(!Files.isRegularFile(file)||file.equals(marker))continue;
                    String key="file."+legacy.relativize(file).toString().replace('\\','/');
                    if(!sha(file).equals(record.getProperty(key)))return false;
                    actual.add(key);
                }
            }
            return actual.equals(record.stringPropertyNames().stream().filter(k->k.startsWith("file.")).collect(java.util.stream.Collectors.toSet()));
        }
    }
}
