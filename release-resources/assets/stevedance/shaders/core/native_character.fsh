#version 150
uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform vec3 NativeAmbient,NativeDiffuse,NativeColor,NativeAdd,NativeDirection;
uniform int LinearAlbedo;
uniform int HalfLambert;
in vec2 texCoord;
in vec4 vertexColor;
in vec3 viewNormal;
out vec4 fragColor;
void main(){
    vec4 base=texture(Sampler0,texCoord)*vertexColor*ColorModulator;if(base.a<.01)discard;
    // YSM paint is display encoded. Decode albedo before source radiance and the
    // shared tone pass; alpha remains coverage. Explicit opt-in preserves older PV profiles.
    if(LinearAlbedo==1){vec3 c=max(base.rgb,vec3(0));base.rgb=mix(c/12.92,pow((c+.055)/1.055,vec3(2.4)),step(vec3(.04045),c));}
    // PSV chara.00000000.fp multiplies color RGB by itself; it does not use EOTF.
    else if(LinearAlbedo==2)base.rgb*=base.rgb;
    vec3 normal=normalize(viewNormal);if(HalfLambert==0&&!gl_FrontFacing)normal=-normal;
    float nd=dot(normal,NativeDirection);
    // The original VP addresses CHARA_TONE at (N.L+1)/2. The tone texture is
    // still unresolved here; this explicit profile uses a linear ramp, not an
    // assertion that the original toon/specular material has been reproduced.
    float diffuse=HalfLambert!=0?clamp(nd*.5+.5,0,1):max(0,nd);
    fragColor=vec4(base.rgb*(NativeAmbient+NativeDiffuse*diffuse)*NativeColor+NativeAdd,base.a);
}
