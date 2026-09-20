package com.example.auto_measure_luoji.feature.camera.ui.previewcapture

/**
 * 仅供算法取帧，不改变屏幕上的 HLG10 输出，也不是显示器色调映射的复制品。
 * HLG 逆 OETF 依 BT.2100：E'=0..1，E=E'^2/3 (E'<=0.5)，
 * 否则 E=(exp((E'-c)/a)+b)/12；a=0.17883277,b=1-4a,c=0.55991073。
 * 在线性域将 BT.2020 D65 转为 BT.709 D65，负分量裁零。
 * 固定曝光系数 4，亮度 Y=0.2126R+0.7152G+0.0722B，应用 L/(1+Y) 压缩高光，
 * 最后编码 sRGB。映射逐像素，不做局部增强或几何变换；
 * 色域裁剪及亮度变化可能改变阈值区域和估计质心，须用真实绿光回归，不能沿用 SDR 精度结论。
 * 参考：https://registry.khronos.org/DataFormat/specs/1.4/dataformat.1.4.html
 */
internal object PreviewCaptureShader {
    const val FRAGMENT = """
        precision highp float;
        uniform sampler2D uTexture;
        uniform bool uHlg;
        varying vec2 vTexCoord;
        float inverseHlg(float v) {
            return v <= 0.5 ? v * v / 3.0 : (exp((v - 0.55991073) / 0.17883277) + 0.28466892) / 12.0;
        }
        float srgb(float v) {
            return v <= 0.0031308 ? 12.92 * v : 1.055 * pow(v, 1.0 / 2.4) - 0.055;
        }
        void main() {
            vec3 c = texture2D(uTexture, vTexCoord).rgb;
            if (uHlg) {
                vec3 l = vec3(inverseHlg(c.r), inverseHlg(c.g), inverseHlg(c.b));
                vec3 rgb = max(vec3(
                    dot(l, vec3(1.660491, -0.587641, -0.072850)),
                    dot(l, vec3(-0.124550, 1.132900, -0.008349)),
                    dot(l, vec3(-0.018151, -0.100579, 1.118730))), vec3(0.0)) * 4.0;
                rgb /= 1.0 + dot(rgb, vec3(0.2126, 0.7152, 0.0722));
                rgb = clamp(rgb, 0.0, 1.0);
                c = vec3(srgb(rgb.r), srgb(rgb.g), srgb(rgb.b));
            }
            gl_FragColor = vec4(c, 1.0);
        }
    """
}
