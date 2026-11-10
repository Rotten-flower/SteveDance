#version 150
uniform sampler2D Sampler0;
uniform int BlendMode;
uniform float Brightness;
uniform int Grayscale;
in vec2 texCoord;
in vec4 vertexColor;
out vec4 fragColor;
void main(){
    vec4 t=texture(Sampler0,texCoord);
    vec4 c=t*vertexColor;
    if(BlendMode==6){
        // Multiply attenuates from white; empty areas must stay neutral.
        c.rgb=max(vec3(0),vec3(1)+(c.rgb-vec3(1))*Brightness)*c.a;
    }else if(BlendMode==-6){
        // A composed multiplier already contains its children's alpha.
        c.rgb=mix(vec3(1),t.rgb,vertexColor.a);
    }else c.rgb*=Brightness;
    if(Grayscale!=0)c.rgb=vec3(dot(c.rgb,vec3(.299,.587,.114))*.72);
    fragColor=c;
}
