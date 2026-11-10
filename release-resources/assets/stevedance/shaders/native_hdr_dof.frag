#version 150
uniform sampler2D Image,Depth;
uniform vec2 Texel,DepthProjection;
uniform vec4 Focus;
uniform float PixelScale;
in vec2 uv;out vec4 fragColor;
float distanceAt(vec2 p){float d=texture(Depth,p).r*2-1;return abs(DepthProjection.y/(d+DepthProjection.x));}
float coc(float z){float d=max(abs(z-Focus.x)-Focus.y,0);return (1-exp(-4.5*d*d/(Focus.z*Focus.z)))*Focus.w;}
void main(){
    float z=distanceAt(uv),amount=clamp(coc(z),0,1);float radius=amount*8*PixelScale;
    vec3 sum=texture(Image,uv).rgb;float weight=1;
    // Bounded disk gather. Avoid pulling a sharp foreground silhouette into a blurred background.
    for(int i=0;i<24;i++){
        float a=float(i)*2.39996323;float r=sqrt((float(i)+.5)/24)*radius;
        vec2 p=uv+vec2(cos(a),sin(a))*r*Texel;
        float sampleZ=distanceAt(p);float w=sampleZ<z-max(.02,z*.015)&&coc(sampleZ)<amount*.5?0:1;
        sum+=texture(Image,p).rgb*w;weight+=w;
    }
    fragColor=vec4(sum/weight,amount);
}
