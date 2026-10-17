#version 330

// 本模组屏幕视觉「模糊」的片元着色器（26.1.2 版）。
//
// 26.1.2 的后处理改成了「管线 + UBO」模型：
//   - 采样器名必须与 RenderPipeline 上 withSampler(...) 的名字一致（这里是 InSampler）；
//   - uniform 不再是逐个 setUniform，而是一整块 std140 UBO，
//     块名与 RenderPipeline 上 withUniform(名字, UNIFORM_BUFFER) 一致（这里是 BlurConfig）；
//   - 顶点坐标由顶点着色器（原版 core/screenquad）用 gl_VertexID 生成，
//     因此这里不需要也不该读 Position 属性。
//
// 布局（必须与 ScreenBlurRenderer.UNIFORM_SIZE 完全一致）：
//   vec2 Step;    // 一个像素在 UV 空间的位移，方向由 CPU 侧决定（横或纵）
//   float Radius; // 本趟的模糊半径（像素）
//
// 依赖 GL_LINEAR 采样：按 2 像素步长取样把采样数减半，最后再补一个半权重的像素
// （与 1.21.1 的 box_blur 同一算法，保持观感一致）。

uniform sampler2D InSampler;

layout(std140) uniform BlurConfig {
    vec2 Step;
    float Radius;
};

in vec2 texCoord;

out vec4 fragColor;

void main() {
    float actualRadius = round(Radius);

    if (actualRadius < 0.5) {
        // CPU 侧已经过滤掉这种情况（MIN_VISIBLE_RADIUS），这里只做兜底：
        // 半径不足 1 像素时直接原样输出，省掉一整个循环
        fragColor = texture(InSampler, texCoord);
        return;
    }

    vec4 blurred = vec4(0.0);

    for (float offset = -actualRadius + 0.5; offset <= actualRadius; offset += 2.0) {
        blurred += texture(InSampler, texCoord + Step * offset);
    }

    // 采样数总是奇数（actualRadius * 2 + 1），最后一个补半权重
    blurred += texture(InSampler, texCoord + Step * actualRadius) / 2.0;

    fragColor = blurred / (actualRadius + 0.5);
}
