#version 150

// 本模组屏幕视觉「模糊」的片元着色器。
// 取自原版 assets/minecraft/shaders/program/box_blur.fsh（1.21.1），
// 仅改名为本模组命名空间以免与原版 / 其它模组的链共用程序对象。
// 依赖 GL_LINEAR 采样：按 2 像素步长取样，把采样数减半，最后补一个半权重的像素。

uniform sampler2D DiffuseSampler;

in vec2 texCoord;
in vec2 sampleStep;

uniform float Radius;
uniform float RadiusMultiplier;

out vec4 fragColor;

void main() {
    vec4 blurred = vec4(0.0);
    float actualRadius = round(Radius * RadiusMultiplier);
    for (float a = -actualRadius + 0.5; a <= actualRadius; a += 2.0) {
        blurred += texture(DiffuseSampler, texCoord + sampleStep * a);
    }
    blurred += texture(DiffuseSampler, texCoord + sampleStep * actualRadius) / 2.0;
    fragColor = blurred / (actualRadius + 0.5);
}
