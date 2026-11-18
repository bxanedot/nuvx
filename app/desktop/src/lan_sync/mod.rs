//! LAN Sync: phones on the same Wi-Fi sync Nuvx playlists straight with this computer. Off until turned on.
pub mod connect;
pub mod crypto;
pub mod files;
pub mod host;
pub mod replay;
#[cfg(test)]
mod host_tests;
