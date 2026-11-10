#version 150
uniform sampler2D Image;
uniform vec2 Step;
uniform vec3 Threshold;
uniform int Extract;
in vec2 uv;out vec4 fragColor;
vec3 radiance(vec2 p){vec3 c=texture(Image,p).rgb;return Extract==1?max(c-Threshold,vec3(0)):Extract==2?max(c,vec3(0)):c;}
void main(){
    vec3 c=radiance(uv)*.227027;
    c+=(radiance(uv+Step*1.384615)+radiance(uv-Step*1.384615))*.316216;
    c+=(radiance(uv+Step*3.230769)+radiance(uv-Step*3.230769))*.070270;
    fragColor=vec4(c,1);
}
