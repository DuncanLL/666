package viewpoint.render;
import java.util.*;
import java.util.regex.*;

/** Original MRT2 pixel-motion output -> Native UV/previous-depth velocity. No material identity rules. */
public final class AimohNativeMotionBridge {
    public record Layout(boolean output,String previousClip,boolean zero){}
    public static Layout inspect(String source,String identity){
        if(!source.contains("SV_Target2"))return new Layout(false,null,false);
        Matcher output=Pattern.compile("SV_Target2\\.xy\\s*=\\s*(\\w+)\\.xy\\s*\\*").matcher(source);
        String motion=null;int position=-1;while(output.find()){motion=output.group(1);position=output.start();}
        if(motion==null){
            if(Pattern.compile("SV_Target2(?:\\.xy)?\\s*=\\s*vec[24]\\(0(?:\\.0+)?(?:[,)]|\\s)").matcher(source).find())return new Layout(true,null,true);
            throw new IllegalArgumentException("Original MRT2 layout has not been reviewed: "+identity);
        }
        String before=source.substring(0,position),previous=null;
        Matcher difference=Pattern.compile(Pattern.quote(motion)+"\\.xy\\s*=\\s*\\(-([A-Za-z0-9_]+)\\.xy\\)\\s*\\+\\s*([A-Za-z0-9_]+)\\.xy\\s*;").matcher(before);
        String previousValue=null;int subtract=-1;while(difference.find()){previousValue=difference.group(1);subtract=difference.start();}
        if(previousValue!=null){
            Matcher divide=Pattern.compile(Pattern.quote(previousValue)+"\\.xy\\s*=\\s*(vs_TEXCOORD\\d+)\\.xy\\s*/\\s*\\1\\.ww\\s*;").matcher(before.substring(0,subtract));
            while(divide.find())previous=divide.group(1);
        }
        if(previous==null)throw new IllegalArgumentException("Original previous-clip MRT2 consumer is unresolved: "+identity);
        return new Layout(true,previous,false);
    }
    public static String adapt(String source,Layout layout){
        if(!layout.output)return source;
        int main=source.indexOf("void main()");if(main<0||main!=source.lastIndexOf("void main()"))throw new IllegalArgumentException("Native motion wrapper needs one source entry");
        source=source.substring(0,main)+"uniform vec2 gf2NativeMotionViewport;\nuniform vec2 gf2NativeMotionJitter;\nuniform int gf2HistoryEnabled;\n"+source.substring(main).replace("void main()","void gf2NativeMotionSource()");
        String result=layout.zero?"SV_Target2=vec4(0.0);":
            "if(gf2HistoryEnabled==0||"+layout.previousClip+".w<=0.0){SV_Target2=vec4(0.0);}else{vec2 velocity=vec2(SV_Target2.x,-SV_Target2.y)/gf2NativeMotionViewport+gf2NativeMotionJitter;SV_Target2=vec4(velocity,"+layout.previousClip+".w,1.0);}";
        return source+"\nvoid main(){gf2NativeMotionSource();"+result+"}\n";
    }
}
