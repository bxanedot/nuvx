//! LAN Sync: phones on the same Wi-Fi sync Nuvx playlists straight with this computer. Off until turned on.
pub mod bluetooth;
// Protocol rules shared with every nuvx app live in the core.
pub use nuvx_core::lan::{connect, crypto};
pub mod files;
pub mod host;
pub mod replay;
#[cfg(test)]
mod host_tests;
