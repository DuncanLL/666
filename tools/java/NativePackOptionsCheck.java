package viewpoint.render;

/** Check the exact host header composition without needing an OpenGL context. */
public final class NativePackOptionsCheck {
    public static void main(String[] args){
        String block="layout(std140) uniform PackOptions { vec4 uPackOptions[16]; };";
        String source="#version 430\n"+block+"\nvoid main(){}";
        if(!AimohGf2OriginalProgram.singlePackOptions(source).equals(source))throw new AssertionError("Single header changed");
        if(!AimohGf2OriginalProgram.singlePackOptions("void main(){}").equals("void main(){}"))throw new AssertionError("Literal stage changed");
        String combined=source+"\n"+block.replace(" ","\n")+"\n"+block;
        String normalized=AimohGf2OriginalProgram.singlePackOptions(combined);
        if(normalized.indexOf("uniform PackOptions")!=normalized.lastIndexOf("uniform PackOptions"))throw new AssertionError("Duplicate header retained");
        if(!normalized.equals(source+"\n\n"))throw new AssertionError("Stage body changed");
        try{AimohGf2OriginalProgram.singlePackOptions(source+block.replace("[16]","[32]"));throw new AssertionError("Conflicting header accepted");}
        catch(IllegalStateException expected){}
        System.out.println("NATIVE_PACK_OPTIONS_COMPOSITION_PASS");
    }
}
