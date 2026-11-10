#version 150
uniform sampler2D Image,FocusImage,Bloom0,Bloom1,Bloom2,Bloom3,Depth;
uniform vec3 BloomColor,Gamma,FogColor;
uniform vec2 DepthProjection;
uniform float ToneExposure,ToneGamma,Saturation,Contrast;
uniform int IntensityBloom;
uniform vec4 Fade,Fog;
in vec2 uv;out vec4 fragColor;
// Reconstruction of the pinned MikuMikuModel YCC lookup. Preserve chroma separately
// from luminance; direct per-channel compression washes out the original colored lamps.
vec3 sourceTone(vec3 inputColor){
    vec3 c=max(inputColor,vec3(0));float exposure=max(ToneExposure,0);
    float y=dot(c,vec3(.30,.59,.11));float radiance=y*exposure;
    if(radiance<.000001)return vec3(0);
    float mapped=pow(1-exp(-radiance),max(.01,ToneGamma*1.5));
    // GLSL pow is undefined for a negative base, even with an integer-valued
    // exponent. The source lookup squares four times and is defined below .5.
    float edge=mapped*2-1;edge*=edge;edge*=edge;edge*=edge;edge*=edge;
    float chromaScale=mapped/max(radiance,.000001)*(1-edge);
    vec2 chroma=(c.rb-vec2(y))*exposure*chromaScale;
    vec3 result=vec3(chroma.x,mapped,chroma.y);
    result.g=dot(result,vec3(-.508475,1,-.186441));result.rb+=mapped;
    return result;
}
void main(){
    vec4 original=texture(Image,uv),focus=texture(FocusImage,uv);
    vec3 c=mix(original.rgb,focus.rgb,focus.a);
    vec3 glow=IntensityBloom!=0?(texture(Bloom0,uv).rgb*.15+(texture(Bloom1,uv).rgb+texture(Bloom2,uv).rgb+texture(Bloom3,uv).rgb)*.25)*.5:(texture(Bloom0,uv).rgb+texture(Bloom1,uv).rgb+texture(Bloom2,uv).rgb+texture(Bloom3,uv).rgb)*.25;
    c+=glow*BloomColor;
    float z=abs(DepthProjection.y/(texture(Depth,uv).r*2-1+DepthProjection.x));
    float f=0;
    if(Fog.w>0&&Fog.z>0){
        float rangeDepth=clamp((z-Fog.x)/max(.01,Fog.y-Fog.x),0,1);
        if(Fog.w<1.5)f=rangeDepth*Fog.z;
        else if(Fog.w<2.5)f=1-exp(-Fog.z*z);
        else if(Fog.w<3.5){float opticalDepth=Fog.z*z;f=1-exp(-opticalDepth*opticalDepth);}
        // F2nd type 4 has a stored interval and is outside the pinned FT enum.
        // Preserve the earlier song import's interval-based reconstruction;
        // interpreting its density as 1/metre blankets Tsugai in opaque black fog.
        else if(Fog.w<4.5){float opticalDepth=Fog.z*rangeDepth;f=1-exp(-opticalDepth*opticalDepth);}
    }
    c=mix(c,FogColor,clamp(f,0,1));
    // Preserve HDR until bloom and exposure are complete. CCRT exposure already enters ToneExposure once.
    c=sourceTone(c);
    float luminance=dot(c,vec3(.30,.59,.11));c=mix(vec3(luminance),c,Saturation);
    vec3 value=max(Gamma+vec3(Contrast),vec3(.125));vec3 start=vec3(0),end=vec3(1);
    for(int i=0;i<3;i++){if(value[i]<2)start[i]=pow(.075,value[i]*.5);else if(value[i]>2)end[i]=pow(.95,value[i]*.5);}
    c=(c-start)/max(end-start,vec3(.0001));
    c=mix(c,Fade.rgb,clamp(Fade.a,0,1));fragColor=vec4(c,original.a);
}
