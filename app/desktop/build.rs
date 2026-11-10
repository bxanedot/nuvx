/// The build number, raised with every release (Android's version code).
const VERSION_CODE: u64 = 7;

// Embed the app icon into the Windows executable (Explorer, taskbar, shortcuts).
fn main() {
    println!("cargo:rerun-if-changed=assets/icon/icon.ico");
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("windows") {
        // FILEVERSION is major.minor.patch.build, 16 bits each.
        let v: Vec<u64> = env!("CARGO_PKG_VERSION").split('.').map(|n| n.parse().unwrap_or(0)).collect();
        let packed = (v[0] << 48) | (v[1] << 32) | (v[2] << 16) | VERSION_CODE;
        winresource::WindowsResource::new()
            .set_icon("assets/icon/icon.ico")
            .set_version_info(winresource::VersionInfo::FILEVERSION, packed)
            .set_version_info(winresource::VersionInfo::PRODUCTVERSION, packed)
            .set_manifest(MANIFEST)
            .compile()
            .expect("failed to embed Windows icon");
    }
}

// The segment heap returns freed memory to the OS far more readily than the default
// NT heap, which keeps the resident footprint down after bursts (images, JSON).
const MANIFEST: &str = r#"<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<assembly xmlns="urn:schemas-microsoft-com:asm.v1" manifestVersion="1.0">
  <application xmlns="urn:schemas-microsoft-com:asm.v3">
    <windowsSettings>
      <heapType xmlns="http://schemas.microsoft.com/SMI/2020/WindowsSettings">SegmentHeap</heapType>
    </windowsSettings>
  </application>
</assembly>
"#;
