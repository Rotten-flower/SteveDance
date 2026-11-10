#version 150
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform int MatteType;
in vec2 texCoord;
in vec4 vertexColor;
out vec4 fragColor;
void main(){vec4 source=texture(Sampler0,texCoord);vec4 mask=texture(Sampler1,texCoord);float m=MatteType<=2?mask.a:dot(mask.rgb,vec3(0.2126,0.7152,0.0722));if(MatteType==2||MatteType==4)m=1.0-m;fragColor=source*m*vertexColor;}
