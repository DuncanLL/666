import java.nio.*;
import java.nio.file.*;
import org.json.JSONObject;
import viewpoint.render.AimohGf2DisplayTables;

/** The shared implementation uses no character identity and preserves zero without artistic parameters. */
public final class DisplayTablesCheck {
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static void save(Path file,ByteBuffer bytes)throws Exception{byte[] result=new byte[bytes.remaining()];bytes.duplicate().get(result);Files.write(file,result);}
    public static void main(String[] args)throws Exception{
        byte[] source=Files.readAllBytes(Path.of(args[0]));Path out=Path.of(args[1]);Files.createDirectories(out);long start=System.nanoTime();
        var a=AimohGf2DisplayTables.build(source,new AimohGf2DisplayTables.Settings(1,1,.25f));double ms=(System.nanoTime()-start)/1e6;
        require(a.linear().remaining()==262144&&a.encoded().remaining()==262144,"Shared table sizes differ");save(out.resolve("shared-linear.rgba16f"),a.linear());save(out.resolve("shared-encoded.rgba16f"),a.encoded());
        var black=AimohGf2DisplayTables.build(new byte[262144],new AimohGf2DisplayTables.Settings(1,1,.25f));
        ByteBuffer b=black.encoded().duplicate().order(ByteOrder.nativeOrder());while(b.hasRemaining()){for(int c=0;c<3;c++)require(Math.abs(Float.float16ToFloat(b.getShort()))<1e-5,"Black became emissive");b.getShort();}
        try{new AimohGf2DisplayTables.Settings(1,0,.25f);throw new AssertionError("Non-invertible saturation accepted");}catch(IllegalArgumentException expected){}
        byte[] invalid=source.clone();invalid[0]=0;invalid[1]=0x7c;
        try{AimohGf2DisplayTables.build(invalid,new AimohGf2DisplayTables.Settings(1,1,.25f));throw new AssertionError("Non-finite source accepted");}catch(IllegalArgumentException expected){}
        var changed=AimohGf2DisplayTables.build(source,new AimohGf2DisplayTables.Settings(1.2f,.9f,.1f));require(!changed.encoded().equals(a.encoded()),"Native option changes did not update transfer");
        JSONObject result=new JSONObject().put("status","SHARED_DISPLAY_TABLES_CHECKED").put("default_prepare_ms",ms).put("GPU_bytes",524288).put("character_ids",0).put("game_started",false);Files.writeString(out.resolve("result.json"),result.toString(2));System.out.println(result);
    }
}
