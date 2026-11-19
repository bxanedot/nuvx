//! nuvx-core: the one Rust home for rules every nuvx app must agree on. Pure (no I/O, no platform APIs), so it
//! builds for desktop, iOS (with app/ios/native-core) and, later, Android through FFI. Each module's behaviour is
//! pinned by shared golden cases in `core/contracts/`, which the Kotlin apps are tested against too.

pub mod lan;
pub mod lyrics;
pub mod model;
pub mod playlist_file;
pub mod recovery;
pub mod release;
