package viewpoint.render;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.*;
/** Host-version ABI only; source character formulas and material values are untouched. */
final class AimohGf2Vertex {
    private static final String NATIVE=AimohGf2NativeShaders.modelVertex();
    private static final Pattern DECL=Pattern.compile("\\b(?:(flat|smooth|noperspective)\\s+)?(in|out)\\s+(\\w+)\\s+(\\w+)\\s*;");
    private static final Pattern LOCATION=Pattern.compile("layout\\s*\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)\\s*out\\s+(\\w+)\\s+(\\w+)\\s*;");
    record Stages(String vertex,String fragment){}
    static String adapt(String source){
        source=AimohGf2OutlineBridge.adapt(source);
        int split=source.indexOf("#extension GL_ARB_explicit_attrib_location : require");
        int bridge=source.indexOf("// Six RGBA32F texels per current PMX vertex");
        if(split<0||bridge<split)
            throw new IllegalArgumentException("Unreviewed GF2 native vertex ABI");
        String head=source.substring(0,split);
        boolean legacy=head.contains("uniform samplerBuffer uPalettes;")&&head.contains("uniform samplerBuffer uDraws;");
        boolean ssbo=head.contains("readonly buffer ModelPalettes { vec4 uPalettes[]; };")&&head.contains("readonly buffer ModelDraws { vec4 uDraws[]; };");
        if(legacy==ssbo)throw new IllegalArgumentException("Unreviewed GF2 model storage ABI");
        String original=source.substring(split,bridge),tail=source.substring(bridge);
        if(ssbo)tail=Pattern.compile("\\b(uPalettes|uDraws)\\[([^\\]]+)\\]").matcher(tail).replaceAll("texelFetch($1,$2)");
        if(tail.contains("uPalettes[")||tail.contains("uDraws["))
            throw new IllegalArgumentException("Unmapped GF2 Workshop texture-buffer fetch");
        return NATIVE+original+tail;
    }
    static Stages bindStages(String vertex,String fragment){
        int split=vertex.indexOf("#extension GL_ARB_explicit_attrib_location : require"),bridge=vertex.indexOf("// Six RGBA32F texels per current PMX vertex");
        String original=vertex.substring(split,bridge);Set<String> supplied=new HashSet<>();int next=0;
        Matcher explicit=LOCATION.matcher(original);while(explicit.find()){supplied.add(explicit.group(3));next=Math.max(next,Integer.parseInt(explicit.group(1))+1);}
        Map<String,String> types=new LinkedHashMap<>(),qualifiers=new HashMap<>();Matcher inputs=DECL.matcher(fragment);
        while(inputs.find())if(inputs.group(2).equals("in")&&!supplied.contains(inputs.group(4))){String name=inputs.group(4);if(types.put(name,inputs.group(3))!=null)throw new IllegalArgumentException("Duplicate source fragment input: "+name);qualifiers.put(name,inputs.group(1)==null?"":inputs.group(1));}
        Map<String,Integer> locations=new LinkedHashMap<>();for(String name:types.keySet())locations.put(name,next++);
        Set<String> matched=new HashSet<>();String host=exports(vertex.substring(0,split),types,qualifiers,locations,matched),tail=exports(vertex.substring(bridge),types,qualifiers,locations,matched);
        if(!matched.equals(types.keySet()))throw new IllegalArgumentException("Missing Native stage outputs: "+types.keySet()+" / "+matched);
        inputs=DECL.matcher(fragment);StringBuilder fs=new StringBuilder();while(inputs.find())if(inputs.group(2).equals("in")&&locations.containsKey(inputs.group(4)))inputs.appendReplacement(fs,Matcher.quoteReplacement("layout(location = "+locations.get(inputs.group(4))+") "+inputs.group()));inputs.appendTail(fs);
        return new Stages(host+original+tail,fs.toString());
    }
    private static String exports(String text,Map<String,String> types,Map<String,String> qualifiers,Map<String,Integer> locations,Set<String> matched){
        Matcher outputs=DECL.matcher(text);StringBuilder result=new StringBuilder();while(outputs.find())if(outputs.group(2).equals("out")){
            String type=outputs.group(3),name=outputs.group(4),qualifier=outputs.group(1)==null?"":outputs.group(1),replacement;
            if(types.containsKey(name)){
                if(!type.equals(types.get(name))||!qualifier.equals(qualifiers.get(name))||!matched.add(name))throw new IllegalArgumentException("Native stage interface differs: "+name);
                replacement="layout(location = "+locations.get(name)+") "+outputs.group();
            }else replacement=type+" "+name+";";
            outputs.appendReplacement(result,Matcher.quoteReplacement(replacement));
        }outputs.appendTail(result);return result.toString();
    }
    private AimohGf2Vertex(){}
}
