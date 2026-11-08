package viewpoint.render;

import java.io.*;
import java.nio.*;
import java.security.*;
import java.util.*;
import org.json.JSONObject;
import org.lwjgl.opengl.*;
import viewpoint.platform.*;

/** Shared character-only display transfer. Native atmosphere, night adaptation and scene post stay in place. */
final class AimohNativeDisplayTransfer implements AutoCloseable {
    private static final String RESOURCE="/aimoh/gf2/bridge/gf2-original-display.rgba16f";
    private final byte[] original;
    private final int[] textures=new int[2];
    private AimohGf2DisplayTables.Settings current;
    private AimohNativePackSelection.DisplaySettings previousSettings;
    private int lastOutput=Integer.MIN_VALUE,outputEncoding;
    private String lastStatus="";
    private int builds;
    private boolean active;
    AimohNativeDisplayTransfer(JSONObject profile)throws IOException{
        if(profile.getInt("format")!=1||profile.getInt("width")!=1024||profile.getInt("height")!=32)throw new IOException("Unsupported original display profile shape");
        try(InputStream input=AimohNativeDisplayTransfer.class.getResourceAsStream(RESOURCE)){
            if(input==null)throw new IOException("Shared original display profile is absent from rendering support");original=input.readAllBytes();
        }
        try{if(original.length!=262144||!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)).equals(profile.getString("sha256")))throw new IOException("Shared original display profile checksum differs");}
        catch(NoSuchAlgorithmException e){throw new IOException(e);}
    }
    private void status(String value){if(!value.equals(lastStatus)){lastStatus=value;System.out.println("[R116 Native] original display: "+value);}}
    private boolean srgbOutput(FrameContext frame){
        if(lastOutput!=frame.outputDrawFbo){
            lastOutput=frame.outputDrawFbo;
            if(lastOutput!=0)outputEncoding=GL45.glGetNamedFramebufferAttachmentParameteri(lastOutput,GL30.GL_COLOR_ATTACHMENT0,GL30.GL_FRAMEBUFFER_ATTACHMENT_COLOR_ENCODING);
            else{
                int previous=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,0);
                try{int attachment=GL11.glGetBoolean(GL11.GL_DOUBLEBUFFER)?GL11.GL_BACK_LEFT:GL11.GL_FRONT_LEFT;outputEncoding=GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER,attachment,GL30.GL_FRAMEBUFFER_ATTACHMENT_COLOR_ENCODING);}
                finally{GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,previous);}
            }
            System.out.println("[R116 Native] output FBO="+lastOutput+"; colour encoding="+outputEncoding+"; framebuffer sRGB="+GL11.glIsEnabled(GL30.GL_FRAMEBUFFER_SRGB));
        }
        return outputEncoding==GL21.GL_SRGB&&GL11.glIsEnabled(GL30.GL_FRAMEBUFFER_SRGB);
    }
    boolean prepare(FrameContext frame,GlProgram program){
        var actual=AimohNativePackSelection.displaySettings();
        if(actual==null||!actual.filmic()){active=false;status("retained Native response: unsupported or changing tone profile");return false;}
        if(!Float.isFinite(actual.strength())||actual.strength()<=0){active=false;status("old character display path selected");return false;}
        AimohGf2DisplayTables.Settings settings;
        try{settings=actual==previousSettings&&current!=null?current:new AimohGf2DisplayTables.Settings(actual.contrast(),actual.saturation(),actual.vibrance());}
        catch(IllegalArgumentException e){active=false;status("retained Native response: non-invertible colour settings");return false;}
        previousSettings=actual;
        if(!settings.equals(current)){
            var tables=AimohGf2DisplayTables.build(original,settings);
            try(AimohGf2UnpackScope scope=new AimohGf2UnpackScope()){
                for(int i=0;i<2;i++){
                    if(textures[i]==0){textures[i]=GL45.glCreateTextures(GL11.GL_TEXTURE_2D);GL45.glTextureStorage2D(textures[i],1,GL30.GL_RGBA16F,1024,32);
                        GL45.glTextureParameteri(textures[i],GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_LINEAR);GL45.glTextureParameteri(textures[i],GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_LINEAR);
                        GL45.glTextureParameteri(textures[i],GL11.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE);GL45.glTextureParameteri(textures[i],GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);}
                    GL45.glTextureSubImage2D(textures[i],0,0,0,1024,32,GL11.GL_RGBA,GL30.GL_HALF_FLOAT,i==0?tables.linear():tables.encoded());
                }
            }
            current=settings;builds++;status("shared original profile ready; "+settings);
        }
        int texture=textures[srgbOutput(frame)?0:1];
        program.sampler("gf2TransferLut",4);program.set("gf2TransferExposure",EyeMeter.exposure(frame)*actual.exposure());program.set("gf2TransferStrength",Math.min(actual.strength(),1));
        GL13.glActiveTexture(GL13.GL_TEXTURE0+4);GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);GL33.glBindSampler(4,0);
        if(!active){status("shared original profile ready; "+settings);active=true;}
        return true;
    }
    JSONObject evidence(){return new JSONObject().put("table_builds",builds).put("GPU_table_bytes",textures[0]==0?0:524288).put("output_encoding",outputEncoding).put("status",lastStatus);}
    @Override public void close(){for(int texture:textures)if(texture!=0)GL11.glDeleteTextures(texture);Arrays.fill(textures,0);current=null;AimohNativePackSelection.clearDisplaySettings();}
}
