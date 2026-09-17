#version 150

// 本模组屏幕视觉「模糊」的顶点着色器。
// 取自原版 assets/minecraft/shaders/program/blur.vsh（1.21.1），
// 仅改名为本模组命名空间；负责把 1 像素（InSize 倒数）换算成按 BlurDir 方向的采样步长。

in vec4 Position;

uniform mat4 ProjMat;
uniform vec2 InSize;
uniform vec2 OutSize;
uniform vec2 BlurDir;

out vec2 texCoord;
out vec2 sampleStep;

void main() {
    vec4 outPos = ProjMat * vec4(Position.xy, 0.0, 1.0);
    gl_Position = vec4(outPos.xy, 0.2, 1.0);

    vec2 oneTexel = 1.0 / InSize;
    sampleStep = oneTexel * BlurDir;

    texCoord = Position.xy / OutSize;
}
