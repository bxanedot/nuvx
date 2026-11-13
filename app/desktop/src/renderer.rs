use std::path::Path;
use std::sync::OnceLock;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Renderer {
    OpenGl,
    Wgpu,
}

static ACTIVE: OnceLock<Renderer> = OnceLock::new();

pub fn init(data_dir: &Path) -> Renderer {
    let mut r = load(data_dir);
    if r == Renderer::Wgpu && !available() {
        log::warn!("renderer: no {} adapter; drawing with OpenGL", api_name());
        r = Renderer::OpenGl;
    }
    let _ = ACTIVE.set(r);
    r
}

pub fn active() -> Renderer {
    *ACTIVE.get_or_init(default_renderer)
}

pub fn load(data_dir: &Path) -> Renderer {
    match std::fs::read_to_string(data_dir.join("renderer")).unwrap_or_default().trim() {
        "wgpu" => Renderer::Wgpu,
        "opengl" => Renderer::OpenGl,
        _ => default_renderer(),
    }
}

fn default_renderer() -> Renderer {
    #[cfg(target_os = "macos")]
    return Renderer::Wgpu;
    #[cfg(not(target_os = "macos"))]
    Renderer::OpenGl
}

pub fn save(data_dir: &Path, r: Renderer) -> std::io::Result<()> {
    std::fs::write(data_dir.join("renderer"), if r == Renderer::Wgpu { "wgpu" } else { "opengl" })
}

pub fn api_name() -> &'static str {
    #[cfg(target_os = "macos")]
    return "Metal";
    #[cfg(not(target_os = "macos"))]
    "Vulkan"
}

pub fn backends() -> wgpu::Backends {
    #[cfg(target_os = "macos")]
    return wgpu::Backends::METAL;
    #[cfg(not(target_os = "macos"))]
    wgpu::Backends::VULKAN
}

pub fn wgpu_options() -> eframe::egui_wgpu::WgpuConfiguration {
    let mut setup = eframe::egui_wgpu::WgpuSetupCreateNew::without_display_handle();
    setup.instance_descriptor.backends = backends();
    eframe::egui_wgpu::WgpuConfiguration { wgpu_setup: eframe::egui_wgpu::WgpuSetup::CreateNew(setup), ..Default::default() }
}

fn available() -> bool {
    let mut desc = wgpu::InstanceDescriptor::new_without_display_handle();
    desc.backends = backends();
    let instance = wgpu::Instance::new(desc);
    let Ok(rt) = tokio::runtime::Builder::new_current_thread().build() else { return false };
    let adapter = rt.block_on(instance.request_adapter(&wgpu::RequestAdapterOptions::default()));
    adapter.is_ok()
}
