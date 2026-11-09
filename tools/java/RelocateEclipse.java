import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import net.bytebuddy.jar.asm.*;
import net.bytebuddy.jar.asm.commons.*;

/** Relocate renderer classes within their package so access to verified host APIs is unchanged. */
public final class RelocateEclipse {
    public static void main(String[] args)throws IOException{
        Path target=Path.of(args[2]);
        Map<String,String> names=new HashMap<>();
        for(String line:Files.readAllLines(Path.of(args[1]))){
            String[] pair=line.split("=",2);
            names.put(pair[0],pair[1]);
        }
        Remapper mapper=new Remapper(){
            @Override public String map(String name){
                int nested=name.indexOf('$');
                String root=nested<0?name:name.substring(0,nested);
                String replacement=names.get(root);
                return replacement==null?name:replacement+(nested<0?"":name.substring(nested));
            }
            @Override public Object mapValue(Object value){
                if(value instanceof String name){
                    if(name.indexOf('/')>=0)return map(name);
                    if(name.indexOf('.')>=0)return map(name.replace('.','/')).replace('/','.');
                }
                return super.mapValue(value);
            }
        };
        try(ZipFile input=new ZipFile(args[0])){
            for(String name:args[3].split(",")){
                var entry=input.getEntry(name);
                if(entry==null)throw new IOException("Missing Eclipse entry "+name);
                ClassReader reader;
                try(var bytes=input.getInputStream(entry)){reader=new ClassReader(bytes);}
                ClassWriter writer=new ClassWriter(0);
                reader.accept(new ClassRemapper(writer,mapper),0);
                Path output=target.resolve(mapper.map(reader.getClassName())+".class");
                Files.createDirectories(output.getParent());
                Files.write(output,writer.toByteArray());
            }
        }
    }
}
