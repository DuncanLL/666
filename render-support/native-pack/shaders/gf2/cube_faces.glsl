#ifndef GF2_NATIVE_CUBE_FACES
#define GF2_NATIVE_CUBE_FACES
vec3 cubeDirection(int face,vec2 p){
    if(face==0)return vec3(1,-p.y,-p.x);
    if(face==1)return vec3(-1,-p.y,p.x);
    if(face==2)return vec3(p.x,1,p.y);
    if(face==3)return vec3(p.x,-1,-p.y);
    if(face==4)return vec3(p.x,-p.y,1);
    return vec3(-p.x,-p.y,-1);
}
#endif
