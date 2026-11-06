package aimoh.pmxpreview;

import aimoh.motion.PmxDrawMesh;

/** Render-thread sampling of completed meshes, using the host's pose phase. Never steps animation or physics. */
final class PmxPresentation {
    private float[][] points;
    private float[] normals,header,lastRotations;
    private int side,sequence;
    private boolean history;
    private float token0,token1,actor;

    PmxDrawMesh.FrameData sample(PmxDrawMesh.FrameData current,PmxDrawMesh.FrameData previous,
            PmxDrawMesh.FrameData older,float phase,boolean cut) {
        int count=current.positions().length;
        if(points==null||points[0].length!=count){points=new float[][]{new float[count],new float[count]};normals=new float[count];history=false;}
        if(!Float.isFinite(phase))phase=1;
        PmxDrawMesh.FrameData a=previous,b=current;float weight=Math.min(phase,1);
        if(cut||a==null||a.positions().length!=count){a=b;weight=1;history=false;}
        else if(weight<0){b=a;a=older!=null&&older.positions().length==count?older:b;weight=Math.max(0,1+weight);}
        float[] output=points[side^1],oldOutput=points[side],ap=a.positions(),bp=b.positions(),an=a.normals(),bn=b.normals();
        if(a==b||weight==1){System.arraycopy(bp,0,output,0,count);System.arraycopy(bn,0,normals,0,count);}
        else if(weight==0){System.arraycopy(ap,0,output,0,count);System.arraycopy(an,0,normals,0,count);}
        else {
            for(int i=0;i<count;i++)output[i]=ap[i]+(bp[i]-ap[i])*weight;
            for(int i=0;i<count;i+=3){
                float x=an[i]+(bn[i]-an[i])*weight,y=an[i+1]+(bn[i+1]-an[i+1])*weight,z=an[i+2]+(bn[i+2]-an[i+2])*weight;
                float length=(float)Math.sqrt(x*x+y*y+z*z);
                if(length<1e-8f){normals[i]=bn[i];normals[i+1]=bn[i+1];normals[i+2]=bn[i+2];}
                else{normals[i]=x/length;normals[i+1]=y/length;normals[i+2]=z/length;}
            }
        }
        float[] source=current.shadingHead();
        if(source==null){header=null;history=false;}
        else {
            if(header==null||header.length!=source.length){header=new float[source.length];history=false;}
            System.arraycopy(source,0,header,0,source.length);
            float[] ah=a.shadingHead(),bh=b.shadingHead();
            boolean compatible=ah!=null&&bh!=null&&ah.length==source.length&&bh.length==source.length;
            boolean dan=source.length>27&&source[26]==42137;
            if(dan)compatible=compatible&&ah[26]==42137&&bh[26]==42137&&ah[24]==source[24]&&bh[24]==source[24];
            if(compatible) {
                for(int i=0;i<12;i++)if(i!=3&&i!=7&&i!=11)header[i]=ah[i]+(bh[i]-ah[i])*weight;
                normalize(header,0);normalize(header,4);
                if(dan) {
                    int start=(int)source[17]*4,length=(int)source[16]*12;
                    for(int i=start;i<start+length;i++)header[i]=ah[i]+(bh[i]-ah[i])*weight;
                } else for(int i=16;i<header.length;i++)header[i]=ah[i]+(bh[i]-ah[i])*weight;
            }
            if(dan) {
                int start=(int)source[17]*4,length=(int)source[16]*12,oldStart=(int)source[27]*4,positions=(int)source[18];
                if(start<0||oldStart<0||positions<0||start+length>header.length||oldStart+length>header.length||positions+count>header.length)
                    throw new IllegalArgumentException("PMX presentation history layout differs");
                boolean use=history&&!cut&&token0==source[12]&&token1==source[13]&&actor==source[24]&&lastRotations!=null&&lastRotations.length==length;
                System.arraycopy(use?oldOutput:output,0,header,positions,count);
                if(use)System.arraycopy(lastRotations,0,header,oldStart,length);
                else System.arraycopy(header,start,header,oldStart,length);
                if(lastRotations==null||lastRotations.length!=length)lastRotations=new float[length];
                System.arraycopy(header,start,lastRotations,0,length);
                header[19]=use?1:0;header[25]=sequence=sequence%0xffffff+1;
                token0=source[12];token1=source[13];actor=source[24];history=true;
            } else history=false;
        }
        side^=1;
        // This upload view is render-thread scratch; immutable game snapshots remain untouched.
        return new PmxDrawMesh.FrameData(output,normals,current.materials(),header);
    }
    private static void normalize(float[] values,int at){
        float x=values[at],y=values[at+1],z=values[at+2],length=(float)Math.sqrt(x*x+y*y+z*z);
        if(length>1e-8f){values[at]=x/length;values[at+1]=y/length;values[at+2]=z/length;}
    }
    void clear(){points=null;normals=null;header=null;lastRotations=null;history=false;}
}
