package com.kang.imageeditapp.render

internal object ColorShader {
    const val VERTEX = """
        attribute vec2 aPosition;
        attribute vec2 aTextureCoordinate;
        varying vec2 vTextureCoordinate;
        void main() {
            gl_Position = vec4(aPosition, 0.0, 1.0);
            vTextureCoordinate = aTextureCoordinate;
        }
    """

    // Android bitmaps are premultiplied. Unpremultiply before grading and output straight
    // RGBA; readback converts it through Bitmap.setPixels, which premultiplies exactly once.
    val FRAGMENT = """
        precision highp float;
        varying vec2 vTextureCoordinate;
        uniform sampler2D uSource;
        uniform sampler2D uCurves;
        uniform vec4 uAdjust1;
        uniform vec4 uAdjust2;
        uniform vec3 uHsl[8];

        vec3 rgbToHsl(vec3 c) {
            float hi = max(max(c.r, c.g), c.b);
            float lo = min(min(c.r, c.g), c.b);
            float delta = hi - lo;
            float lightness = (hi + lo) * 0.5;
            if (delta < 0.00001) return vec3(0.0, 0.0, lightness);
            float hue;
            if (hi == c.r) hue = (c.g - c.b) / delta;
            else if (hi == c.g) hue = (c.b - c.r) / delta + 2.0;
            else hue = (c.r - c.g) / delta + 4.0;
            hue = fract(hue / 6.0 + 1.0);
            float saturation = delta / max(0.00001, 1.0 - abs(2.0 * lightness - 1.0));
            return vec3(hue, clamp(saturation, 0.0, 1.0), lightness);
        }

        vec3 hslToRgb(vec3 hsl) {
            vec3 base = clamp(abs(mod(hsl.x * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);
            float chroma = (1.0 - abs(2.0 * hsl.z - 1.0)) * hsl.y;
            return (base - 0.5) * chroma + hsl.z;
        }

        float hueWeight(float hue, float center, float leftWidth, float rightWidth) {
            float distance = fract(hue - center + 0.5) - 0.5;
            return max(0.0, 1.0 - abs(distance) / (distance < 0.0 ? leftWidth : rightWidth));
        }

        void addHsl(float hue, float center, float leftWidth, float rightWidth,
                    vec3 adjustment, inout vec3 result, inout float total) {
            float weight = hueWeight(hue, center, leftWidth, rightWidth);
            result += adjustment * weight;
            total += weight;
        }

        float shiftUnit(float value, float adjustment) {
            return clamp(value + adjustment * (adjustment >= 0.0 ? 1.0 - value : value), 0.0, 1.0);
        }

        void main() {
            vec4 source = texture2D(uSource, vTextureCoordinate);
            float alpha = source.a;
            vec3 color = alpha > 0.00001 ? source.rgb / alpha : vec3(0.0);
            color *= exp2(uAdjust1.x);
            color += uAdjust1.y * 0.25;
            color = (color - 0.5) * (1.0 + uAdjust1.z) + 0.5;
            float luminance = clamp(dot(color, vec3(0.2126, 0.7152, 0.0722)), 0.0, 1.0);
            color = mix(vec3(luminance), color, 1.0 + uAdjust1.w);
            color += vec3(uAdjust2.x * 0.15 + uAdjust2.y * 0.05,
                          -uAdjust2.y * 0.10,
                          -uAdjust2.x * 0.15 + uAdjust2.y * 0.05);
            color += uAdjust2.z * (1.0 - luminance) * (1.0 - luminance) * 0.30;
            color += uAdjust2.w * luminance * luminance * 0.30;
            color = clamp(color, 0.0, 1.0);

            vec3 hsl = rgbToHsl(color);
            vec3 adjustment = vec3(0.0);
            float weight = 0.0;
            // Each band has full influence at its named color and blends into its
            // neighbors. The asymmetric spans reflect the actual eight hue centers.
            addHsl(hsl.x, 0.0, 0.16666667, 0.08333333, uHsl[0], adjustment, weight);
            addHsl(hsl.x, 0.08333333, 0.08333333, 0.08333333, uHsl[1], adjustment, weight);
            addHsl(hsl.x, 0.16666667, 0.08333333, 0.16666667, uHsl[2], adjustment, weight);
            addHsl(hsl.x, 0.33333333, 0.16666667, 0.16666667, uHsl[3], adjustment, weight);
            addHsl(hsl.x, 0.5, 0.16666667, 0.16666667, uHsl[4], adjustment, weight);
            addHsl(hsl.x, 0.66666667, 0.16666667, 0.08333333, uHsl[5], adjustment, weight);
            addHsl(hsl.x, 0.75, 0.08333333, 0.08333333, uHsl[6], adjustment, weight);
            addHsl(hsl.x, 0.83333333, 0.08333333, 0.16666667, uHsl[7], adjustment, weight);
            adjustment /= max(weight, 0.00001);
            // Keep neutral pixels neutral unless their lightness is explicitly edited.
            hsl.x = fract(hsl.x + adjustment.x / 6.0 + 1.0);
            hsl.y = hsl.y < 0.00001 ? 0.0 : shiftUnit(hsl.y, adjustment.y);
            hsl.z = shiftUnit(hsl.z, adjustment.z * hsl.y);
            color = clamp(hslToRgb(hsl), 0.0, 1.0);

            color.r = texture2D(uCurves, vec2((color.r * 255.0 + 0.5) / 256.0, 0.5)).r;
            color.g = texture2D(uCurves, vec2((color.g * 255.0 + 0.5) / 256.0, 0.5)).g;
            color.b = texture2D(uCurves, vec2((color.b * 255.0 + 0.5) / 256.0, 0.5)).b;
            gl_FragColor = vec4(color, alpha);
        }
    """.trimIndent()
}
