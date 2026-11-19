//! LAN Sync and Nuvx Connect protocol pieces every host and phone must agree on byte for byte: the pairing and
//! envelope crypto (P-256 ECDH, HKDF-SHA256, AES-256-GCM) and the Connect command/report formats.
//! Android's Kotlin side (`LanSyncCrypto`, `NuvxConnect`) is tested to match.

pub mod connect;
pub mod crypto;
