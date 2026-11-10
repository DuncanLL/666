package aimoh.motion;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** Check bounded local input before handing it to the native VMD reader. */
public final class VmdInput {
    private static final Charset NAMES=Charset.forName("windows-31j");

    private static long count(DataInputStream stream) throws IOException {
        return Integer.toUnsignedLong(Integer.reverseBytes(stream.readInt()));
    }

    private static void finite(ByteBuffer data,int start,int end) throws IOException {
        for(int at=start;at<end;at+=4)if(!Float.isFinite(data.getFloat(at)))throw new IOException("VMD 含无效数值");
    }

    public static Set<String> inspect(Path path) throws IOException {
        long size=Files.size(path);
        if(size<58||size>128L*1024*1024)throw new IOException("VMD 文件不完整或超过 128 MiB");
        try(var stream=new DataInputStream(new BufferedInputStream(Files.newInputStream(path),65536))) {
            String header=new String(stream.readNBytes(30),0,25,StandardCharsets.US_ASCII);
            if(!header.equals("Vocaloid Motion Data 0002")&&!header.equals("Vocaloid Motion Data file"))
                throw new IOException("文件没有有效的 VMD 文件头");
            stream.skipNBytes(20);
            long bones=count(stream);
            if(bones>(size-58)/111)throw new IOException("VMD 骨骼区不完整");
            byte[] key=new byte[111];ByteBuffer data=ByteBuffer.wrap(key).order(ByteOrder.LITTLE_ENDIAN);
            for(long i=0;i<bones;i++){stream.readFully(key);finite(data,19,47);}
            long morphs=count(stream);
            if(morphs>(size-58-bones*111)/23)throw new IOException("VMD 表情区不完整");
            if(bones==0&&morphs==0)throw new IOException("请选择人物动作或表情 VMD");
            Set<String> names=new HashSet<>();
            for(long i=0;i<morphs;i++) {
                stream.readFully(key,0,23);finite(data,19,23);
                int length=0;while(length<15&&key[length]!=0)length++;
                names.add(new String(key,0,length,NAMES));
            }
            for(int width:new int[]{61,28,9})if(stream.available()>0)stream.skipNBytes(count(stream)*width);
            if(stream.available()>0) {
                long switches=count(stream);
                if(switches>stream.available()/9)throw new IOException("VMD IK 区不完整");
                for(long i=0;i<switches;i++){stream.skipNBytes(5);stream.skipNBytes(count(stream)*21);}
            }
            return names;
        }
    }
}
