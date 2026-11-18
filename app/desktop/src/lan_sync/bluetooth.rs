//! LAN Sync over Bluetooth (RFCOMM): the same sealed requests as Wi-Fi, for when the phone and this computer share
//! no network. Pairing, encryption, approval and revocation are unchanged; Bluetooth only carries the bytes. The
//! Windows Bluetooth pairing of the two devices is the user's own, done once in the system settings.

/// The nuvx LAN Sync service the phone looks up by UUID. Fixed: phones and computers must agree on it.
#[allow(dead_code)] // documents the string the phone side uses; the code uses SERVICE_GUID
pub const SERVICE_UUID: &str = "6e757678-4c61-6e53-796e-630000000001";
#[cfg(windows)]
const SERVICE_GUID: u128 = 0x6e757678_4c61_6e53_796e_630000000001;

#[cfg(windows)]
mod imp {
    use std::io::{Read, Write};
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::{Arc, Mutex};
    use std::time::Duration;
    use windows::Win32::Devices::Bluetooth::{AF_BTH, BTHPROTO_RFCOMM, NS_BTH, SOCKADDR_BTH};
    use windows::Win32::Networking::WinSock::{
        accept, bind, closesocket, getsockname, listen, recv, send, setsockopt, socket, WSASetServiceW, WSAStartup,
        CSADDR_INFO, RNRSERVICE_DELETE, RNRSERVICE_REGISTER, SEND_RECV_FLAGS, SOCKADDR, SOCKET, SOCKET_ADDRESS,
        SOCK_STREAM, SOL_SOCKET, SO_RCVTIMEO, WSADATA, WSAQUERYSETW,
    };
    use windows::core::{GUID, PWSTR};

    use super::super::host::Inner;

    /// An accepted RFCOMM connection; [BtReader] and [BtWriter] read and write it like a TCP stream.
    struct BtStream(SOCKET);

    impl Drop for BtStream {
        fn drop(&mut self) {
            unsafe { closesocket(self.0) };
        }
    }

    /// Starts the Bluetooth listener; returns this computer's Bluetooth address ("AA:BB:CC:DD:EE:FF") for phones.
    /// Fails quietly (None) on a computer without Bluetooth.
    pub fn serve(inner: Arc<Mutex<Inner>>, stop: Arc<AtomicBool>, wake: Arc<dyn Fn() + Send + Sync>) -> Option<String> {
        unsafe {
            let mut data = WSADATA::default();
            if WSAStartup(0x0202, &mut data) != 0 {
                log::info!("lan sync: no Bluetooth (sockets unavailable)");
                return None;
            }
            let listener = match socket(AF_BTH as i32, SOCK_STREAM, BTHPROTO_RFCOMM as i32) {
                Ok(s) => s,
                Err(e) => {
                    log::info!("lan sync: no Bluetooth: {e}");
                    return None;
                }
            };
            let mut addr = SOCKADDR_BTH { addressFamily: AF_BTH, btAddr: 0, serviceClassId: GUID::zeroed(), port: u32::MAX };
            let len = std::mem::size_of::<SOCKADDR_BTH>() as i32;
            if bind(listener, &addr as *const _ as *const SOCKADDR, len) != 0 || listen(listener, 4) != 0 {
                log::info!("lan sync: Bluetooth listener not started: {}", std::io::Error::last_os_error());
                closesocket(listener);
                return None;
            }
            let mut got = len;
            if getsockname(listener, &mut addr as *mut _ as *mut SOCKADDR, &mut got) != 0 {
                log::info!("lan sync: Bluetooth address unknown: {}", std::io::Error::last_os_error());
                closesocket(listener);
                return None;
            }
            let mut service = GUID::from_u128(super::SERVICE_GUID);
            let mut name: Vec<u16> = "nuvx LAN Sync\0".encode_utf16().collect();
            let mut csaddr = CSADDR_INFO {
                LocalAddr: SOCKET_ADDRESS { lpSockaddr: &mut addr as *mut _ as *mut SOCKADDR, iSockaddrLength: len },
                RemoteAddr: SOCKET_ADDRESS::default(),
                iSocketType: SOCK_STREAM.0,
                iProtocol: BTHPROTO_RFCOMM as i32,
            };
            let query = WSAQUERYSETW {
                dwSize: std::mem::size_of::<WSAQUERYSETW>() as u32,
                lpszServiceInstanceName: PWSTR(name.as_mut_ptr()),
                lpServiceClassId: &mut service,
                dwNameSpace: NS_BTH,
                dwNumberOfCsAddrs: 1,
                lpcsaBuffer: &mut csaddr,
                ..Default::default()
            };
            if WSASetServiceW(&query, RNRSERVICE_REGISTER, 0) != 0 {
                log::warn!("lan sync: could not advertise the Bluetooth service: {}", std::io::Error::last_os_error());
                closesocket(listener);
                return None;
            }
            let (raw_addr, channel) = (addr.btAddr, addr.port); // copied out: the struct is packed
            let bt = format_address(raw_addr);
            log::info!("lan sync: Bluetooth on {bt}, channel {channel}");
            let raw = listener.0;
            std::thread::Builder::new()
                .name("lan-sync-bt".into())
                .spawn(move || {
                    let listener = SOCKET(raw);
                    while !stop.load(Ordering::Relaxed) {
                        let Ok(conn) = accept(listener, None, None) else {
                            std::thread::sleep(Duration::from_millis(500));
                            continue;
                        };
                        let timeout = 60_000u32.to_le_bytes();
                        setsockopt(conn, SOL_SOCKET, SO_RCVTIMEO, Some(&timeout));
                        let (inner, wake) = (inner.clone(), wake.clone());
                        std::thread::spawn(move || {
                            let stream = BtStream(conn);
                            let mut reader = std::io::BufReader::new(BtReader(&stream));
                            let mut out = BtWriter(&stream);
                            // One connection carries many requests: Bluetooth takes a second or two to connect.
                            while let Ok(true) = super::super::host::respond(&mut reader, &mut out, &inner, &*wake) {}
                        });
                    }
                    let mut service = GUID::from_u128(super::SERVICE_GUID);
                    let gone = WSAQUERYSETW {
                        dwSize: std::mem::size_of::<WSAQUERYSETW>() as u32,
                        lpServiceClassId: &mut service,
                        dwNameSpace: NS_BTH,
                        ..Default::default()
                    };
                    WSASetServiceW(&gone, RNRSERVICE_DELETE, 0);
                    closesocket(listener);
                })
                .ok()?;
            Some(bt)
        }
    }

    struct BtReader<'a>(&'a BtStream);
    impl Read for BtReader<'_> {
        fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
            let n = unsafe { recv((self.0).0, buf, SEND_RECV_FLAGS(0)) };
            if n < 0 { Err(std::io::Error::last_os_error()) } else { Ok(n as usize) }
        }
    }

    struct BtWriter<'a>(&'a BtStream);
    impl Write for BtWriter<'_> {
        fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
            let n = unsafe { send((self.0).0, buf, SEND_RECV_FLAGS(0)) };
            if n < 0 { Err(std::io::Error::last_os_error()) } else { Ok(n as usize) }
        }
        fn flush(&mut self) -> std::io::Result<()> {
            Ok(())
        }
    }

    pub(super) fn format_address(raw: u64) -> String {
        (0..6).rev().map(|i| format!("{:02X}", (raw >> (i * 8)) & 0xff)).collect::<Vec<_>>().join(":")
    }
}

#[cfg(windows)]
pub use imp::serve;

/// Bluetooth for LAN Sync is Windows-only for now; elsewhere the computer is reached over Wi-Fi.
#[cfg(not(windows))]
pub fn serve(
    _inner: std::sync::Arc<std::sync::Mutex<super::host::Inner>>,
    _stop: std::sync::Arc<std::sync::atomic::AtomicBool>,
    _wake: std::sync::Arc<dyn Fn() + Send + Sync>,
) -> Option<String> {
    None
}

#[cfg(all(test, windows))]
mod tests {
    #[test]
    fn bluetooth_addresses_print_most_significant_byte_first() {
        assert_eq!(super::imp::format_address(0x0011_2233_4455), "00:11:22:33:44:55");
        assert_eq!(super::imp::format_address(0xA1B2_C3D4_E5F6), "A1:B2:C3:D4:E5:F6");
    }

    #[test]
    fn the_service_uuid_parses() {
        let g = windows::core::GUID::from_u128(super::SERVICE_GUID);
        assert_ne!(g, windows::core::GUID::zeroed());
    }
}
