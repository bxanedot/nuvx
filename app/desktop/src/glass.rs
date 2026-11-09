//! Liquid glass for the player bar, the Android app's `liquidGlass` (LiquidGlass.kt and
//! the vendored backdrop library) on OpenGL: what's already drawn under the bar is
//! copied, saturated 1.5×, Gaussian-blurred at a third of the resolution, then seen
//! through a rounded-rect lens (refraction, depth and chromatic dispersion, the
//! library's own shader), tinted, with the directional rim highlight on top.
//! wgpu draws the old frosted pane instead.

use std::sync::{Arc, Mutex};

use eframe::glow::{self, HasContext};
use egui::{Color32, Rect};

/// LiquidGlass.kt: blur 8dp, lens height and amount 24dp, a third of the resolution.
const BLUR_DP: f32 = 8.0;
const LENS_DP: f32 = 24.0;
const SCALE: f32 = 3.0;
/// How far around the bar the lens and the blur read.
pub const MARGIN: f32 = LENS_DP + 3.0 * BLUR_DP;

const VERTEX: &str = r#"
void main() {
  vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"#;

const BLUR: &str = r#"
uniform sampler2D u_tex;
uniform vec2 u_size;
uniform vec2 u_step;
uniform float u_sigma;
uniform float u_saturation;
void main() {
  vec2 uv = gl_FragCoord.xy / u_size;
  vec4 acc = vec4(0.0);
  float sum = 0.0;
  for (int i = -24; i <= 24; i++) {
    float f = float(i);
    if (abs(f) > 3.0 * u_sigma) continue;
    float w = exp(-0.5 * f * f / (u_sigma * u_sigma));
    acc += TEX(u_tex, uv + u_step * f) * w;
    sum += w;
  }
  vec4 c = acc / sum;
  // Android's ColorMatrix.setSaturation weights.
  float l = dot(c.rgb, vec3(0.213, 0.715, 0.072));
  // Premultiplied, as egui draws: a see-through (Mica, Acrylic) window keeps its alpha.
  FRAG = vec4(clamp(mix(vec3(l), c.rgb, u_saturation), 0.0, c.a), c.a);
}
"#;

// RoundedRectRefractionWithDispersionShaderString and DefaultHighlightShaderString from
// app/android/.../backdrop/internal/Shaders.kt, in y-down coordinates centred on the bar.
const LENS: &str = r#"
uniform sampler2D u_tex;
uniform vec4 u_src;
uniform vec2 u_center;
uniform vec2 u_half;
uniform float u_radius;
uniform float u_height;
uniform float u_amount;
uniform float u_rim;
uniform vec4 u_tint;

float sdRoundedRect(vec2 coord, vec2 halfSize, float radius) {
  vec2 cornerCoord = abs(coord) - (halfSize - vec2(radius));
  float outside = length(max(cornerCoord, 0.0)) - radius;
  float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
  return outside + inside;
}
vec2 gradSdRoundedRect(vec2 coord, vec2 halfSize, float radius) {
  vec2 cornerCoord = abs(coord) - (halfSize - vec2(radius));
  if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
    return sign(coord) * normalize(max(cornerCoord, 0.0) + 1e-6);
  }
  float gradX = step(cornerCoord.y, cornerCoord.x);
  return sign(coord) * vec2(gradX, 1.0 - gradX);
}
float circleMap(float x) { return 1.0 - sqrt(1.0 - x * x); }
vec4 at(vec2 c) {
  vec2 fb = u_center + vec2(c.x, -c.y);
  return TEX(u_tex, (fb - u_src.xy) / u_src.zw);
}

void main() {
  vec2 c = (gl_FragCoord.xy - u_center) * vec2(1.0, -1.0);
  float sd = sdRoundedRect(c, u_half, u_radius);
  float cover = clamp(0.5 - sd, 0.0, 1.0);
  if (cover <= 0.0) discard;
  float gradRadius = min(u_radius * 1.5, min(u_half.x, u_half.y));
  vec4 color;
  if (-sd >= u_height) {
    color = at(c);
  } else {
    float s = min(sd, 0.0);
    float d = circleMap(1.0 - -s / u_height) * u_amount;
    // depthEffect = 1
    vec2 grad = normalize(gradSdRoundedRect(c, u_half, gradRadius) + normalize(c + 1e-6));
    vec2 rc = c + d * grad;
    float dispersion = (c.x * c.y) / (u_half.x * u_half.y);
    vec2 dc = d * grad * dispersion;
    color = vec4(0.0);
    vec4 red = at(rc + dc);                     color.r += red.r / 3.5; color.a += red.a / 7.0;
    vec4 orange = at(rc + dc * (2.0 / 3.0));    color.r += orange.r / 3.5; color.g += orange.g / 7.0; color.a += orange.a / 7.0;
    vec4 yellow = at(rc + dc * (1.0 / 3.0));    color.r += yellow.r / 3.5; color.g += yellow.g / 3.5; color.a += yellow.a / 7.0;
    vec4 green = at(rc);                        color.g += green.g / 3.5; color.a += green.a / 7.0;
    vec4 cyan = at(rc - dc * (1.0 / 3.0));      color.g += cyan.g / 3.5; color.b += cyan.b / 3.0; color.a += cyan.a / 7.0;
    vec4 blue = at(rc - dc * (2.0 / 3.0));      color.b += blue.b / 3.0; color.a += blue.a / 7.0;
    vec4 purple = at(rc - dc);                  color.r += purple.r / 7.0; color.b += purple.b / 3.0; color.a += purple.a / 7.0;
  }
  // Surface tint, then Highlight.Default: a 0.5dp rim, white at 50% × |cos| to a 45° light, added.
  // Premultiplied throughout: the tint goes over what's seen, the rim adds light.
  vec3 rgb = color.rgb * (1.0 - u_tint.a) + u_tint.rgb * u_tint.a;
  float alpha = color.a * (1.0 - u_tint.a) + u_tint.a;
  float rim = clamp(sd + u_rim + 0.5, 0.0, 1.0);
  vec2 g = gradSdRoundedRect(c, u_half, gradRadius);
  float light = 0.5 * abs(dot(g, vec2(0.70710678))) * rim;
  alpha = min(alpha + light, 1.0);
  FRAG = vec4(clamp(rgb + vec3(light), 0.0, alpha) * cover, alpha * cover);
}
"#;

struct Gl {
    blur: glow::Program,
    lens: glow::Program,
    vao: glow::VertexArray,
    /// The copy of the framebuffer, and the two blur passes.
    tex: [glow::Texture; 3],
    fbo: [glow::Framebuffer; 2],
}

/// The bar's glass, drawn from a paint callback. Cheap to clone.
#[derive(Clone)]
pub struct Glass {
    gl: Arc<Mutex<Option<Gl>>>,
}

unsafe fn program(gl: &glow::Context, header: &str, frag: &str) -> anyhow::Result<glow::Program> {
    unsafe {
        let program = gl.create_program().map_err(anyhow::Error::msg)?;
        let mut shaders = Vec::new();
        for (kind, src) in [(glow::VERTEX_SHADER, VERTEX), (glow::FRAGMENT_SHADER, frag)] {
            let s = gl.create_shader(kind).map_err(anyhow::Error::msg)?;
            gl.shader_source(s, &format!("{header}{src}"));
            gl.compile_shader(s);
            anyhow::ensure!(gl.get_shader_compile_status(s), "glass shader: {}", gl.get_shader_info_log(s));
            gl.attach_shader(program, s);
            shaders.push(s);
        }
        gl.link_program(program);
        anyhow::ensure!(gl.get_program_link_status(program), "glass link: {}", gl.get_program_info_log(program));
        for s in shaders {
            gl.detach_shader(program, s);
            gl.delete_shader(s);
        }
        Ok(program)
    }
}

impl Glass {
    pub fn new(gl: &glow::Context) -> anyhow::Result<Self> {
        let version = eframe::egui_glow::ShaderVersion::get(gl);
        // gl_VertexID and integer ops need GLSL 1.30+ / ES 3.00.
        anyhow::ensure!(version.is_new_shader_interface(), "glass needs GLSL 1.30 or ES 3.00");
        let header = format!("{}\nprecision highp float;\nout vec4 frag_color;\n#define FRAG frag_color\n#define TEX texture\n", version.version_declaration());
        unsafe {
            let blur = program(gl, &header, BLUR)?;
            let lens = program(gl, &header, LENS)?;
            let vao = gl.create_vertex_array().map_err(anyhow::Error::msg)?;
            let mut tex = Vec::new();
            for _ in 0..3 {
                let t = gl.create_texture().map_err(anyhow::Error::msg)?;
                gl.bind_texture(glow::TEXTURE_2D, Some(t));
                for (p, v) in [
                    (glow::TEXTURE_MIN_FILTER, glow::LINEAR),
                    (glow::TEXTURE_MAG_FILTER, glow::LINEAR),
                    (glow::TEXTURE_WRAP_S, glow::CLAMP_TO_EDGE),
                    (glow::TEXTURE_WRAP_T, glow::CLAMP_TO_EDGE),
                ] {
                    gl.tex_parameter_i32(glow::TEXTURE_2D, p, v as i32);
                }
                tex.push(t);
            }
            let fbo = [gl.create_framebuffer().map_err(anyhow::Error::msg)?, gl.create_framebuffer().map_err(anyhow::Error::msg)?];
            gl.bind_texture(glow::TEXTURE_2D, None);
            let tex = [tex[0], tex[1], tex[2]];
            Ok(Self { gl: Arc::new(Mutex::new(Some(Gl { blur, lens, vao, tex, fbo }))) })
        }
    }

    /// Paints the glass for a capsule `bar` (radius `radius`), tinted `tint` (unmultiplied).
    pub fn paint(&self, painter: &egui::Painter, bar: Rect, radius: f32, tint: Color32) {
        let state = self.gl.clone();
        let area = bar.expand(MARGIN);
        let cb = eframe::egui_glow::CallbackFn::new(move |info, painter| {
            let gl = painter.gl();
            let guard = state.lock().unwrap();
            let Some(s) = guard.as_ref() else { return };
            let ppp = info.pixels_per_point;
            let [sw, sh] = info.screen_size_px.map(|v| v as i32);
            // The part of the area on screen, in framebuffer pixels (origin bottom-left).
            let x0 = ((area.min.x * ppp).floor() as i32).clamp(0, sw);
            let x1 = ((area.max.x * ppp).ceil() as i32).clamp(0, sw);
            let y0 = (sh - (area.max.y * ppp).ceil() as i32).clamp(0, sh);
            let y1 = (sh - (area.min.y * ppp).floor() as i32).clamp(0, sh);
            let (w, h) = (x1 - x0, y1 - y0);
            if w <= 0 || h <= 0 {
                return;
            }
            let (lw, lh) = (((w as f32) / SCALE).ceil() as i32, ((h as f32) / SCALE).ceil() as i32);
            // Skia's radius → sigma, at the third-resolution scale the Android app blurs at.
            let sigma = 0.57735 * BLUR_DP * ppp / SCALE + 0.5;
            unsafe {
                gl.active_texture(glow::TEXTURE0);
                gl.bind_texture(glow::TEXTURE_2D, Some(s.tex[0]));
                gl.copy_tex_image_2d(glow::TEXTURE_2D, 0, glow::RGBA8, x0, y0, w, h, 0);
                gl.disable(glow::SCISSOR_TEST);
                gl.disable(glow::BLEND);
                gl.bind_vertex_array(Some(s.vao));
                gl.use_program(Some(s.blur));
                let loc = |n| gl.get_uniform_location(s.blur, n);
                gl.uniform_1_i32(loc("u_tex").as_ref(), 0);
                gl.uniform_1_f32(loc("u_sigma").as_ref(), sigma);
                gl.uniform_2_f32(loc("u_size").as_ref(), lw as f32, lh as f32);
                gl.viewport(0, 0, lw, lh);
                // Horizontal from the copy (also scaling it down), then vertical, saturating.
                for (pass, (src, step, sat)) in [(s.tex[0], (1.0 / lw as f32, 0.0), 1.0), (s.tex[1], (0.0, 1.0 / lh as f32), 1.5)].into_iter().enumerate() {
                    let dst = s.tex[pass + 1];
                    gl.bind_texture(glow::TEXTURE_2D, Some(dst));
                    gl.tex_image_2d(glow::TEXTURE_2D, 0, glow::RGBA8 as i32, lw, lh, 0, glow::RGBA, glow::UNSIGNED_BYTE, glow::PixelUnpackData::Slice(None));
                    gl.bind_framebuffer(glow::FRAMEBUFFER, Some(s.fbo[pass]));
                    gl.framebuffer_texture_2d(glow::FRAMEBUFFER, glow::COLOR_ATTACHMENT0, glow::TEXTURE_2D, Some(dst), 0);
                    gl.bind_texture(glow::TEXTURE_2D, Some(src));
                    gl.uniform_2_f32(loc("u_step").as_ref(), step.0, step.1);
                    gl.uniform_1_f32(loc("u_saturation").as_ref(), sat);
                    gl.draw_arrays(glow::TRIANGLES, 0, 3);
                }
                // Back to egui's target, viewport and clip, blending premultiplied.
                gl.bind_framebuffer(glow::FRAMEBUFFER, painter.intermediate_fbo());
                let vp = info.viewport_in_pixels();
                gl.viewport(vp.left_px, vp.from_bottom_px, vp.width_px, vp.height_px);
                let clip = info.clip_rect_in_pixels();
                gl.enable(glow::SCISSOR_TEST);
                gl.scissor(clip.left_px, clip.from_bottom_px, clip.width_px, clip.height_px);
                gl.enable(glow::BLEND);
                gl.blend_func_separate(glow::ONE, glow::ONE_MINUS_SRC_ALPHA, glow::ONE_MINUS_DST_ALPHA, glow::ONE);
                gl.use_program(Some(s.lens));
                let loc = |n| gl.get_uniform_location(s.lens, n);
                gl.bind_texture(glow::TEXTURE_2D, Some(s.tex[2]));
                gl.uniform_1_i32(loc("u_tex").as_ref(), 0);
                gl.uniform_4_f32(loc("u_src").as_ref(), x0 as f32, y0 as f32, w as f32, h as f32);
                let c = bar.center() * ppp;
                gl.uniform_2_f32(loc("u_center").as_ref(), c.x, sh as f32 - c.y);
                gl.uniform_2_f32(loc("u_half").as_ref(), bar.width() * ppp / 2.0, bar.height() * ppp / 2.0);
                gl.uniform_1_f32(loc("u_radius").as_ref(), radius * ppp);
                gl.uniform_1_f32(loc("u_height").as_ref(), LENS_DP * ppp);
                gl.uniform_1_f32(loc("u_amount").as_ref(), LENS_DP * ppp);
                gl.uniform_1_f32(loc("u_rim").as_ref(), 0.5 * ppp);
                let t = tint.to_srgba_unmultiplied().map(|v| v as f32 / 255.0);
                gl.uniform_4_f32(loc("u_tint").as_ref(), t[0], t[1], t[2], t[3]);
                gl.draw_arrays(glow::TRIANGLES, 0, 3);
            }
        });
        painter.add(egui::PaintCallback { rect: area, callback: Arc::new(cb) });
    }

    pub fn destroy(&self, gl: &glow::Context) {
        if let Some(s) = self.gl.lock().unwrap().take() {
            unsafe {
                gl.delete_program(s.blur);
                gl.delete_program(s.lens);
                gl.delete_vertex_array(s.vao);
                for t in s.tex {
                    gl.delete_texture(t);
                }
                for f in s.fbo {
                    gl.delete_framebuffer(f);
                }
            }
        }
    }
}
