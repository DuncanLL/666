package viewpoint.render;

import org.lwjgl.opengl.*;

/** Requirements of the shared original texture reader and fixed GF2 bindings. */
final class AimohGf2Device {
    static String unsupported(){
        var caps=GL.getCapabilities();
        if(!caps.OpenGL43||(!caps.OpenGL45&&!caps.GL_ARB_direct_state_access)
            ||(!caps.OpenGL44&&!caps.GL_ARB_clear_texture))
            return "requires OpenGL 4.3 with direct state access and clear texture";
        int units=GL11.glGetInteger(GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS);
        // The current source passes and state restore use texture unit 148.
        if(units<149)return "requires 149 combined texture units (driver provides "+units+")";
        if(!caps.GL_EXT_texture_compression_s3tc)return "requires S3TC for the original DXT textures";
        return null;
    }

    private AimohGf2Device(){}
}
