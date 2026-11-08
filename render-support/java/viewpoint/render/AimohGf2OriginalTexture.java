package viewpoint.render;

import java.nio.ByteBuffer;
import org.json.*;
import org.lwjgl.opengl.*;

/** Upload original Windows texture payloads without PNG, resizing or new mips.
 * Resource identity is CAB/pathID; identical names are not cache identities.
 */
public final class AimohGf2OriginalTexture implements AutoCloseable {
    public final int texture,width,height,levels;
    public final String resource;
    public AimohGf2OriginalTexture(String resource,JSONObject metadata,ByteBuffer originalPayload){
        this.resource=resource;width=metadata.getInt("m_Width");height=metadata.getInt("m_Height");levels=metadata.getInt("m_MipCount");
        int format=metadata.getInt("m_TextureFormat");boolean srgb=metadata.getInt("m_ColorSpace")==1;
        var caps=GL.getCapabilities();
        if(format==25&&!caps.OpenGL42&&!caps.GL_ARB_texture_compression_bptc)throw new IllegalStateException("GF2 BC7/BPTC texture unsupported by this driver: "+resource);
        if((format==10||format==12)&&!caps.GL_EXT_texture_compression_s3tc)throw new IllegalStateException("GF2 S3TC texture unsupported by this driver: "+resource);
        int internal=switch(format){
            case 25->srgb?GL42.GL_COMPRESSED_SRGB_ALPHA_BPTC_UNORM:GL42.GL_COMPRESSED_RGBA_BPTC_UNORM;
            case 10->srgb?EXTTextureSRGB.GL_COMPRESSED_SRGB_ALPHA_S3TC_DXT1_EXT:EXTTextureCompressionS3TC.GL_COMPRESSED_RGBA_S3TC_DXT1_EXT;
            case 12->srgb?EXTTextureSRGB.GL_COMPRESSED_SRGB_ALPHA_S3TC_DXT5_EXT:EXTTextureCompressionS3TC.GL_COMPRESSED_RGBA_S3TC_DXT5_EXT;
            case 17->GL30.GL_RGBA16F;
            default->throw new IllegalArgumentException("Unmapped original texture format "+format+" "+resource);
        };
        if(width<=0||height<=0||levels<=0||!originalPayload.isDirect())throw new IllegalArgumentException("Invalid original texture payload");
        int maximum=GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);if(width>maximum||height>maximum||levels>32-Integer.numberOfLeadingZeros(Math.max(width,height)))throw new IllegalArgumentException("GF2 texture dimensions/mips exceed driver limits: "+width+"x"+height+" / "+levels+"; max="+maximum+"; "+resource);
        int old=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);texture=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
        try(AimohGf2UnpackScope unpack=new AimohGf2UnpackScope()) {
            int offset=originalPayload.position();
            for(int level=0;level<levels;level++){
                int w=Math.max(1,width>>level),h=Math.max(1,height>>level);
                int bytes=format==17?w*h*8:Math.max(1,(w+3)/4)*Math.max(1,(h+3)/4)*(format==10?8:16);
                if(offset+bytes>originalPayload.limit())throw new IllegalArgumentException("Truncated original mip "+level+" "+resource);
                ByteBuffer view=originalPayload.duplicate();view.position(offset);view.limit(offset+bytes);view=view.slice();
                if(format==17)GL11.glTexImage2D(GL11.GL_TEXTURE_2D,level,internal,w,h,0,GL11.GL_RGBA,GL30.GL_HALF_FLOAT,view);
                else GL13.glCompressedTexImage2D(GL11.GL_TEXTURE_2D,level,internal,w,h,0,view);
                offset+=bytes;
            }
            if(offset!=originalPayload.limit())throw new IllegalArgumentException("Unaccounted original mip bytes "+resource);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL12.GL_TEXTURE_BASE_LEVEL,0);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL12.GL_TEXTURE_MAX_LEVEL,levels-1);
            JSONObject settings=metadata.getJSONObject("m_TextureSettings");int filter=settings.getInt("m_FilterMode");
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,filter==0?GL11.GL_NEAREST:GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,levels==1?(filter==0?GL11.GL_NEAREST:GL11.GL_LINEAR):filter==0?GL11.GL_NEAREST_MIPMAP_NEAREST:filter==1?GL11.GL_LINEAR_MIPMAP_NEAREST:GL11.GL_LINEAR_MIPMAP_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_S,wrap(settings.getInt("m_WrapU")));
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_T,wrap(settings.getInt("m_WrapV")));
            GL11.glTexParameterf(GL11.GL_TEXTURE_2D,GL14.GL_TEXTURE_LOD_BIAS,(float)settings.getDouble("m_MipBias"));
            if(GL.getCapabilities().GL_EXT_texture_filter_anisotropic)GL11.glTexParameterf(GL11.GL_TEXTURE_2D,EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT,
                Math.max(1,Math.min(settings.getInt("m_Aniso"),GL11.glGetFloat(EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT))));
        } catch(RuntimeException e){GL11.glDeleteTextures(texture);throw e;}
        finally {GL11.glBindTexture(GL11.GL_TEXTURE_2D,old);}
    }
    static int wrap(int value){return switch(value){case 0->GL11.GL_REPEAT;case 1->GL12.GL_CLAMP_TO_EDGE;case 2->GL14.GL_MIRRORED_REPEAT;case 3->GL44.GL_MIRROR_CLAMP_TO_EDGE;default->throw new IllegalArgumentException("Original wrap mode "+value);};}
    @Override public void close(){GL11.glDeleteTextures(texture);}
}
