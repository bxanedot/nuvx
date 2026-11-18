//! LAN Sync crypto. Pairing: P-256 ECDH with a hash commitment from the phone (so a man in the middle cannot
//! pick keys that make both screens show the same code), HKDF-SHA256 for the device key and the 6-digit code.
//! Traffic: AES-256-GCM envelopes bound to the device id, with a timestamp and a one-time nonce against replay.
//! `LanSyncCrypto.kt` on Android must produce byte-identical keys, codes and envelopes.

use aes_gcm::aead::{Aead, AeadCore, OsRng, Payload, rand_core::RngCore};
use aes_gcm::{Aes256Gcm, KeyInit};
use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD as B64;
use p256::ecdh::EphemeralSecret;
use p256::{EncodedPoint, PublicKey};
use sha2::{Digest, Sha256};

pub const KEY_INFO: &[u8] = b"nuvx-lan-v1 key";
pub const SAS_INFO: &[u8] = b"nuvx-lan-v1 sas";

pub fn random_bytes<const N: usize>() -> [u8; N] {
    let mut b = [0u8; N];
    OsRng.fill_bytes(&mut b);
    b
}

pub fn b64(bytes: &[u8]) -> String {
    B64.encode(bytes)
}

pub fn unb64(text: &str) -> Option<Vec<u8>> {
    B64.decode(text.trim()).ok()
}

pub fn sha256_hex(bytes: &[u8]) -> String {
    Sha256::digest(bytes).iter().map(|b| format!("{b:02x}")).collect()
}

/// The host's half of a pairing: its one-time key pair.
pub struct HostKey {
    secret: EphemeralSecret,
    pub public: Vec<u8>, // uncompressed SEC1, 65 bytes
}

impl HostKey {
    pub fn new() -> Self {
        let secret = EphemeralSecret::random(&mut OsRng);
        let public = EncodedPoint::from(secret.public_key()).as_bytes().to_vec();
        Self { secret, public }
    }

    /// The device key and the 6-digit code both screens show, or None for a key that is not on the curve.
    pub fn agree(&self, device_public: &[u8]) -> Option<([u8; 32], String)> {
        let theirs = PublicKey::from_sec1_bytes(device_public).ok()?;
        derive(&self.secret.diffie_hellman(&theirs), device_public, &self.public)
    }
}

/// HKDF-SHA256 over the ECDH secret, salted with both public keys (phone first): the device key and the code.
pub fn derive(shared: &p256::ecdh::SharedSecret, device_public: &[u8], host_public: &[u8]) -> Option<([u8; 32], String)> {
    let hk = shared.extract::<Sha256>(Some(&[device_public, host_public].concat()));
    let mut key = [0u8; 32];
    let mut sas = [0u8; 4];
    hk.expand(KEY_INFO, &mut key).ok()?;
    hk.expand(SAS_INFO, &mut sas).ok()?;
    Some((key, format!("{:06}", u32::from_be_bytes(sas) % 1_000_000)))
}

pub fn seal(key: &[u8; 32], aad: &[u8], plain: &[u8]) -> String {
    let cipher = Aes256Gcm::new(key.into());
    let nonce = Aes256Gcm::generate_nonce(&mut OsRng);
    let ct = cipher.encrypt(&nonce, Payload { msg: plain, aad }).expect("AES-GCM encrypt");
    b64(&[nonce.as_slice(), &ct].concat())
}

pub fn open(key: &[u8; 32], aad: &[u8], sealed: &str) -> Option<Vec<u8>> {
    let raw = unb64(sealed)?;
    if raw.len() < 12 + 16 {
        return None;
    }
    let (nonce, ct) = raw.split_at(12);
    Aes256Gcm::new(key.into()).decrypt(nonce.into(), Payload { msg: ct, aad }).ok()
}

#[cfg(test)]
pub mod tests {
    use super::*;

    /// The phone's side of a pairing with the same primitives: the tests' stand-in for Android.
    pub struct PhoneKey {
        secret: EphemeralSecret,
        pub public: Vec<u8>,
    }

    impl PhoneKey {
        pub fn new() -> Self {
            let secret = EphemeralSecret::random(&mut OsRng);
            let public = EncodedPoint::from(secret.public_key()).as_bytes().to_vec();
            Self { secret, public }
        }

        pub fn agree(&self, host_public: &[u8]) -> ([u8; 32], String) {
            let shared = self.secret.diffie_hellman(&PublicKey::from_sec1_bytes(host_public).unwrap());
            let hk = shared.extract::<Sha256>(Some(&[self.public.as_slice(), host_public].concat()));
            let (mut key, mut sas) = ([0u8; 32], [0u8; 4]);
            hk.expand(KEY_INFO, &mut key).unwrap();
            hk.expand(SAS_INFO, &mut sas).unwrap();
            (key, format!("{:06}", u32::from_be_bytes(sas) % 1_000_000))
        }
    }

    #[test]
    fn both_sides_derive_the_same_key_and_code() {
        let host = HostKey::new();
        let phone = PhoneKey::new();
        let (phone_key, phone_sas) = phone.agree(&host.public);
        let (host_key, host_sas) = host.agree(&phone.public).unwrap();
        assert_eq!(host_key, phone_key);
        assert_eq!(host_sas, phone_sas);
        assert_eq!(host_sas.len(), 6);
        assert!(host.agree(b"not a point").is_none());
    }

    /// Fixed keys shared with `LanSyncCryptoTest.kt`, so the Rust and Kotlin halves cannot drift apart.
    pub const VECTOR_PHONE_SCALAR: [u8; 32] = [7; 32];
    pub const VECTOR_HOST_SCALAR: [u8; 32] = [9; 32];

    fn vector() -> (Vec<u8>, Vec<u8>, [u8; 32], String) {
        let phone = p256::SecretKey::from_slice(&VECTOR_PHONE_SCALAR).unwrap();
        let host = p256::SecretKey::from_slice(&VECTOR_HOST_SCALAR).unwrap();
        let phone_pub = EncodedPoint::from(phone.public_key()).as_bytes().to_vec();
        let host_pub = EncodedPoint::from(host.public_key()).as_bytes().to_vec();
        let shared = p256::ecdh::diffie_hellman(host.to_nonzero_scalar(), phone.public_key().as_affine());
        let (key, sas) = derive(&shared, &phone_pub, &host_pub).unwrap();
        (phone_pub, host_pub, key, sas)
    }

    #[test]
    fn the_shared_test_vector_holds() {
        let (phone_pub, host_pub, key, sas) = vector();
        assert_eq!(b64(&phone_pub), "BB4YUy_UdUwC8wQdnHXOszuD_9gax85P6ILMscmLxYlupGwxHE4v9A3ZajZT5uRURdMt_khuztdcepDGoYiBwKM");
        assert_eq!(b64(&host_pub), "BHE1-k_ZOgnc6Yu_aBtL_PUOfA1jVOYq-wv_KjQpYXhl7UwfAt25Aj7lalV-UV1qncZsEfIglg3llDNN9Yh3ZyQ");
        assert_eq!(b64(&key), "He1TxIRwj2hWEvYnwAJYQyqJdgo8rKrN1Ezb8NRta7Q");
        assert_eq!(sas, "954760");
        // Sealed by LanSyncCrypto.kt with the same key and device id.
        let from_kotlin = "wc1bo9kfO7UPo9C_PFVEc8-DrHvcWbecdnrGIcHEkOX6DchM_eAERXOAJu3Z";
        assert_eq!(open(&key, b"dev1", from_kotlin).as_deref(), Some(&b"hello from kotlin"[..]));
        assert!(open(&key, b"dev2", from_kotlin).is_none());
    }

    #[test]
    fn envelopes_open_only_with_the_same_key_and_device() {
        let key = random_bytes::<32>();
        let sealed = seal(&key, b"dev1", b"hello");
        assert_eq!(open(&key, b"dev1", &sealed).as_deref(), Some(&b"hello"[..]));
        assert!(open(&key, b"dev2", &sealed).is_none(), "bound to the device id");
        assert!(open(&random_bytes::<32>(), b"dev1", &sealed).is_none(), "wrong key");
        let mut raw = unb64(&sealed).unwrap();
        *raw.last_mut().unwrap() ^= 1;
        assert!(open(&key, b"dev1", &b64(&raw)).is_none(), "tampered");
    }
}
