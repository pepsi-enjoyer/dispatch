// mDNS/Zeroconf service advertisement (dispatch-ct2.1).
//
// Advertises the console's WebSocket server as `_dispatch._tcp.local.`
// so the Android radio can discover it on the LAN without manual IP entry.

use mdns_sd::{ServiceDaemon, ServiceInfo};
use std::collections::HashMap;

const SERVICE_TYPE: &str = "_dispatch._tcp.local.";

/// Start advertising the console via mDNS. Returns the daemon handle
/// (dropping it stops advertisement).
///
/// If `tls_fingerprint` is provided, it is included as a TXT record (`fp=<hex>`)
/// so the radio app can pin the certificate without manual configuration.
pub fn advertise(port: u16, tls_fingerprint: Option<&str>) -> Option<ServiceDaemon> {
    let mdns = match ServiceDaemon::new() {
        Ok(d) => d,
        Err(e) => {
            eprintln!("mdns: failed to create daemon: {e}");
            return None;
        }
    };

    let hostname = hostname::get()
        .ok()
        .and_then(|h| h.into_string().ok())
        .unwrap_or_else(|| "dispatch-console".to_string());

    let host_label = format!("{hostname}.local.");

    let properties: Option<HashMap<String, String>> = tls_fingerprint.map(|fp| {
        let mut map = HashMap::new();
        map.insert("fp".to_string(), fp.to_string());
        map
    });

    let service = match ServiceInfo::new(
        SERVICE_TYPE,
        &hostname,
        &host_label,
        "",   // empty IP = auto-detect all interfaces
        port,
        properties,
    ) {
        Ok(s) => s.enable_addr_auto(),
        Err(e) => {
            eprintln!("mdns: failed to create service info: {e}");
            return None;
        }
    };

    if let Err(e) = mdns.register(service) {
        eprintln!("mdns: failed to register service: {e}");
        return None;
    }

    eprintln!("mdns: advertising {SERVICE_TYPE} on port {port}");
    Some(mdns)
}
