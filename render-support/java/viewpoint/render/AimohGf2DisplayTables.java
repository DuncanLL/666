package viewpoint.render;

import java.nio.*;

/** Original default-profile LUT composed with the inverse of the selected Native Filmic response. */
public final class AimohGf2DisplayTables {
    public record Settings(float contrast,float saturation,float vibrance) {
        public Settings {if(!Float.isFinite(contrast)||!Float.isFinite(saturation)||!Float.isFinite(vibrance)||contrast<.7f||contrast>1.6f||saturation<=.001f||saturation>2||vibrance<0||vibrance>1)throw new IllegalArgumentException("Native display settings cannot be inverted");}
    }
    public record Tables(ByteBuffer linear,ByteBuffer encoded){}
    private static final int BYTES=1024*32*8,INVERSE_STEPS=4096;
    private static double clamp(double x,double lo,double hi){return Math.max(lo,Math.min(hi,x));}
    private static double smooth(double a,double b,double x){double t=clamp((x-a)/(b-a),0,1);return t*t*(3-2*t);}
    private static double channel(double x,double a,double ad,double b,double c){
        x=clamp(x,0,2);double toned=Math.pow(x,a)/(Math.pow(x,ad)*b+c),keep=.5*(1-smooth(0,.15,x));return toned+(x*(.19/.18)-toned)*keep;
    }
    private static double[] inverseChannels(Settings s){
        double a=s.contrast,ad=a*.9,midA=Math.pow(.18,a),midAd=Math.pow(.18,ad),whiteA=Math.pow(2,a),whiteAd=Math.pow(2,ad),span=(whiteAd-midAd)*.19;
        double b=(whiteA*.19-midA)/span,c=(whiteAd*midA-whiteA*midAd*.19)/span;
        double[] result=new double[INVERSE_STEPS+1];
        for(int i=0;i<=INVERSE_STEPS;i++){double goal=(double)i/INVERSE_STEPS,lo=0,hi=2;for(int j=0;j<24;j++){double x=(lo+hi)*.5;if(channel(x,a,ad,b,c)<goal)lo=x;else hi=x;}result[i]=(lo+hi)*.5;}
        return result;
    }
    private static double inverse(double y,double[] table){double p=clamp(y,0,1)*INVERSE_STEPS;int i=(int)p;return i==INVERSE_STEPS?table[i]:table[i]+(table[i+1]-table[i])*(p-i);}
    private static double encode(double x){return x<=.0031308?12.92*x:1.055*Math.pow(x,1/2.4)-.055;}
    private static void node(double r,double g,double b,Settings s,double[] channel,ByteBuffer output){
        r=clamp(r,0,1);g=clamp(g,0,1);b=clamp(b,0,1);double y=.2126*r+.7152*g+.0722*b,dr=r-y,dg=g-y,db=b-y,lo=1/(1+s.vibrance),hi=1;
        for(int i=0;i<20;i++){double q=(lo+hi)*.5,cr=y+dr*q,cg=y+dg*q,cb=y+db*q,top=Math.max(cr,Math.max(cg,cb));double chroma=top>1e-4?(top-Math.min(cr,Math.min(cg,cb)))/top:0;if(q*(1+s.vibrance*(1-chroma))<1)lo=q;else hi=q;}
        double q=(lo+hi)*.5,factor=s.saturation*(1-.35*(1-smooth(0,.06,y)))*(1-.5*smooth(.9,1,y));
        output.putShort(Float.floatToFloat16((float)inverse(y+dr*q/factor,channel))).putShort(Float.floatToFloat16((float)inverse(y+dg*q/factor,channel))).putShort(Float.floatToFloat16((float)inverse(y+db*q/factor,channel))).putShort(Float.floatToFloat16(1));
    }
    public static Tables build(byte[] original,Settings settings){
        if(original.length!=BYTES)throw new IllegalArgumentException("Original display LUT must be 1024x32 RGBA16F");
        ByteBuffer input=ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN),linear=ByteBuffer.allocateDirect(BYTES).order(ByteOrder.nativeOrder()),encoded=ByteBuffer.allocateDirect(BYTES).order(ByteOrder.nativeOrder());double[] channels=inverseChannels(settings);
        while(input.hasRemaining()){
            float r=Float.float16ToFloat(input.getShort()),g=Float.float16ToFloat(input.getShort()),b=Float.float16ToFloat(input.getShort()),a=Float.float16ToFloat(input.getShort());
            if(!Float.isFinite(r)||!Float.isFinite(g)||!Float.isFinite(b)||!Float.isFinite(a))throw new IllegalArgumentException("Non-finite original display table");
            node(r,g,b,settings,channels,linear);node(encode(clamp(r,0,1)),encode(clamp(g,0,1)),encode(clamp(b,0,1)),settings,channels,encoded);
        }
        linear.flip();encoded.flip();return new Tables(linear,encoded);
    }
    private AimohGf2DisplayTables(){}
}
