//! C ABI bridge exposing the `webrtc` Rust implementation to Java via jextract/FFM.
//!
//! Design notes:
//! - All async `PeerConnection` operations are driven by a process-wide multi-threaded
//!   tokio runtime; every FFI call blocks on the async result (`block_on`).
//! - Handles are opaque pointers (`Box<...>` / `Arc<...>`) passed back to Java as `void*`.
//! - Events (ICE candidate, connection state, data channel, track, data channel messages)
//!   are delivered to Java through C function-pointer callbacks supplied at creation time.

use std::collections::HashMap;
use std::ffi::{c_char, c_void, CStr, CString};
use std::os::raw::c_int;
use std::sync::{Arc, Mutex};

use once_cell::sync::Lazy;
use tokio::runtime::Runtime;

use webrtc::peer_connection::{
    PeerConnection, PeerConnectionBuilder, PeerConnectionEventHandler, RTCConfigurationBuilder,
    RTCPeerConnectionIceEvent,
};
use webrtc::runtime::default_runtime;
use webrtc::data_channel::{DataChannelEvent, RTCDataChannelInit};
use webrtc::media_stream::track_remote::{TrackRemote, TrackRemoteEvent};
use webrtc::media_stream::track_local::TrackLocal;
use webrtc::media_stream::track_local::static_sample::TrackLocalStaticSample;
use webrtc::media_stream::MediaStreamTrack;
use webrtc::rtp_transceiver::RtpSender;
use rtc::media::Sample;
use rtc::rtp_transceiver::rtp_sender::{
    RTCRtpCodec, RTCRtpCodingParameters, RTCRtpEncodingParameters,
};

use rtc::peer_connection::configuration::RTCIceServer;
use rtc::peer_connection::configuration::RTCOfferOptions;
use rtc::peer_connection::configuration::setting_engine::SettingEngine;
use rtc::peer_connection::sdp::RTCSessionDescription;
use rtc::peer_connection::state::{RTCPeerConnectionState, RTCIceGatheringState};
use rtc::peer_connection::transport::{RTCIceCandidateInit, RTCDtlsRole};
use rtc::ice::network_type::NetworkType;
use rtc::rtp_transceiver::rtp_sender::RtpCodecKind;
use rtc::rtp_transceiver::{RTCRtpTransceiverDirection, RTCRtpTransceiverInit};
use rtc::rtcp::payload_feedbacks::picture_loss_indication::PictureLossIndication;
use rtc::statistics::report::RTCStatsReportEntry;

/// Runtime that actually drives each PeerConnection's async work. Callbacks
/// (ICE candidate, state changes, data channel polls) are delivered on its
/// worker threads.
static PC_RUNTIME: Lazy<Runtime> = Lazy::new(|| {
    tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .expect("failed to build peer-connection tokio runtime")
});

/// Separate runtime used to block on async FFI calls. It must NOT be the same
/// runtime that drives the PeerConnections, otherwise a callback firing on a
/// `PC_RUNTIME` worker that re-enters an FFI call would call `block_on` from
/// inside a runtime ("Cannot start a runtime from within a runtime").
static CALL_RUNTIME: Lazy<Runtime> = Lazy::new(|| {
    tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .expect("failed to build call tokio runtime")
});

fn block_on<F: std::future::Future>(fut: F) -> F::Output {
    CALL_RUNTIME.block_on(fut)
}

// ---------------------------------------------------------------------------
// Opaque handle types
// ---------------------------------------------------------------------------

/// A builder-side configuration accumulated before constructing a peer connection.
struct Config {
    ice_servers: Vec<RTCIceServer>,
    /// UDP listen addresses; empty forces TCP-only gathering.
    udp_addrs: Vec<String>,
    /// TCP listen addresses (RFC 4571).
    tcp_addrs: Vec<String>,
    /// DTLS answering role: 0=auto/unspecified, 1=auto, 2=client, 3=server.
    dtls_role: c_int,
    /// Network type bitmask: bit0=udp, bit1=tcp (0 = defaults).
    network_types: c_int,
}

/// Live peer connection plus its event forwarder and open data channels.
struct Peer {
    pc: Arc<dyn PeerConnection>,
    /// Data channels created locally or received remotely, keyed by id.
    /// Shared with the event `Forwarder` so remotely-received channels can be
    /// registered and polled.
    data_channels: Arc<Mutex<HashMap<u16, Arc<dyn webrtc::data_channel::DataChannel>>>>,
    /// Per-channel message/open/close callbacks, keyed by channel id.
    dc_callbacks: Arc<DcCallbackMap>,
}

impl Peer {
    fn new(
        pc: Arc<dyn PeerConnection>,
        data_channels: Arc<Mutex<HashMap<u16, Arc<dyn webrtc::data_channel::DataChannel>>>>,
        dc_callbacks: Arc<DcCallbackMap>,
    ) -> Self {
        Self {
            pc,
            data_channels,
            dc_callbacks,
        }
    }
}

/// Registry of active data-channel message callbacks keyed by channel id.
#[derive(Clone, Copy)]
struct DataChannelCallbacks {
    on_message: Option<extern "C" fn(u16, *const u8, usize)>,
    on_open: Option<extern "C" fn(u16)>,
    on_close: Option<extern "C" fn(u16)>,
}

/// Per-peer registry of data-channel callbacks, keyed by channel id. Shared
/// between the owning `Peer` and its event `Forwarder` so remotely-received
/// channels (which generate their own ids) resolve to the correct callbacks.
type DcCallbackMap = Mutex<HashMap<u16, DataChannelCallbacks>>;

// ---------------------------------------------------------------------------
// C callback function-pointer types (Java supplies these via jextract downcalls)
// ---------------------------------------------------------------------------

/// ICE candidate event. `candidate` is the SDP candidate string; `sdp_mid` may be null.
type IceCandidateCallback = extern "C" fn(*mut c_void, *const c_char, *const c_char);
/// Connection state change (see `RtcPeerConnectionState` enum values).
type ConnectionStateCallback = extern "C" fn(*mut c_void, c_int);
/// Data channel created by the remote peer; surfaced by id + label.
type DataChannelCallback = extern "C" fn(*mut c_void, u16, *const c_char);
/// ICE gathering state change (0=new, 1=gathering, 2=complete).
type IceGatheringStateCallback = extern "C" fn(*mut c_void, c_int);
/// Remote track received; surfaced by track_id + label.
type TrackCallback = extern "C" fn(*mut c_void, u32, *const c_char);

// ---------------------------------------------------------------------------
// String helpers
// ---------------------------------------------------------------------------

/// Allocate a C string the caller must free with [`webrtc_ffi_free_string`].
unsafe fn into_cstring(s: String) -> *mut c_char {
    CString::new(s).unwrap_or_default().into_raw()
}

fn read_str<'a>(p: *const c_char) -> &'a str {
    if p.is_null() {
        ""
    } else {
        unsafe { CStr::from_ptr(p) }.to_str().unwrap_or("")
    }
}

// ---------------------------------------------------------------------------
// Lifecycle / init
// ---------------------------------------------------------------------------

/// Returns a status string confirming the FFI bridge is loaded.
#[no_mangle]
pub extern "C" fn webrtc_ffi_init() -> *mut c_char {
    unsafe { into_cstring("WebRTC FFI Initialized".to_string()) }
}

/// Free a string previously returned by the FFI.
#[no_mangle]
pub extern "C" fn webrtc_ffi_free_string(s: *mut c_char) {
    if s.is_null() {
        return;
    }
    unsafe {
        let _ = CString::from_raw(s);
    }
}

// ---------------------------------------------------------------------------
// Configuration builder
// ---------------------------------------------------------------------------

/// Create an empty configuration handle.
#[no_mangle]
pub extern "C" fn webrtc_ffi_config_create() -> *mut c_void {
    let cfg = Box::new(Config {
        ice_servers: Vec::new(),
        udp_addrs: vec!["0.0.0.0:0".to_string()],
        tcp_addrs: Vec::new(),
        dtls_role: 0,
        network_types: 0,
    });
    Box::into_raw(cfg) as *mut c_void
}

/// Configure transport addresses and DTLS/network options.
///
/// - `udp_addrs` / `tcp_addrs`: space/comma separated listen addresses. An empty
///   string leaves the default (UDP `0.0.0.0:0`); pass an explicit empty list to
///   disable that transport (e.g. empty UDP + a TCP addr = TCP only).
/// - `dtls_role`: 0 unspecified, 1 auto, 2 client, 3 server.
/// - `network_types`: bitmask, bit0=udp, bit1=tcp (0 = library defaults).
#[no_mangle]
pub extern "C" fn webrtc_ffi_config_set_transport(
    cfg: *mut c_void,
    udp_addrs: *const c_char,
    tcp_addrs: *const c_char,
    dtls_role: c_int,
    network_types: c_int,
) -> c_int {
    if cfg.is_null() {
        return -1;
    }
    let parse = |p: *const c_char| -> Vec<String> {
        read_str(p)
            .split(|c| c == ',' || c == ' ' || c == '\n')
            .map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty())
            .collect()
    };
    let udp = parse(udp_addrs);
    let tcp = parse(tcp_addrs);
    unsafe {
        let c = &mut *(cfg as *mut Config);
        // An empty UDP list means "no UDP". The default `0.0.0.0:0` is only
        // applied when neither transport was specified (preserving prior behavior).
        c.udp_addrs = if udp.is_empty() && tcp.is_empty() {
            vec!["0.0.0.0:0".to_string()]
        } else {
            udp
        };
        c.tcp_addrs = tcp;
        c.dtls_role = dtls_role;
        c.network_types = network_types;
    }
    0
}

/// Add a STUN/TURN server (urls separated by commas/spaces, optional user & credential).
#[no_mangle]
pub extern "C" fn webrtc_ffi_config_add_ice_server(
    cfg: *mut c_void,
    urls: *const c_char,
    username: *const c_char,
    credential: *const c_char,
) -> c_int {
    if cfg.is_null() {
        return -1;
    }
    let urls: Vec<String> = read_str(urls)
        .split(|c| c == ',' || c == ' ' || c == '\n')
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .collect();
    if urls.is_empty() {
        return -2;
    }
    let server = RTCIceServer {
        urls,
        username: read_str(username).to_string(),
        credential: read_str(credential).to_string(),
    };
    unsafe {
        (*(cfg as *mut Config)).ice_servers.push(server);
    }
    0
}

/// Free a configuration handle.
#[no_mangle]
pub extern "C" fn webrtc_ffi_config_free(cfg: *mut c_void) {
    if cfg.is_null() {
        return;
    }
    unsafe {
        let _ = Box::from_raw(cfg as *mut Config);
    }
}

// ---------------------------------------------------------------------------
// Peer connection
// ---------------------------------------------------------------------------

/// Event forwarder bridging async `PeerConnectionEventHandler` to C callbacks.
struct Forwarder {
    user_data: *mut c_void,
    on_ice_candidate: Option<IceCandidateCallback>,
    on_connection_state: Option<ConnectionStateCallback>,
    on_data_channel: Option<DataChannelCallback>,
    on_ice_gathering_state_change: Option<IceGatheringStateCallback>,
    on_track: Option<TrackCallback>,
    /// Shared with the owning `Peer` so remotely-received data channels can be
    /// registered for polling.
    data_channels: Arc<Mutex<HashMap<u16, Arc<dyn webrtc::data_channel::DataChannel>>>>,
    /// Per-channel callbacks shared with the owning `Peer`.
    dc_callbacks: Arc<DcCallbackMap>,
}

// Raw pointers make Forwarder !Send/!Sync; the handler is only ever used from the
// runtime threads we control, so we assert the necessary trait impls here.
unsafe impl Send for Forwarder {}
unsafe impl Sync for Forwarder {}

#[async_trait::async_trait]
impl PeerConnectionEventHandler for Forwarder {
    async fn on_ice_candidate(&self, event: RTCPeerConnectionIceEvent) {
        if let Some(cb) = &self.on_ice_candidate {
            // RFC 5245 candidate attribute: foundation prio proto addr port typ ...
            let c = &event.candidate;
            let tcp_suffix = if c.protocol == rtc::peer_connection::transport::RTCIceProtocol::Tcp {
                match c.tcp_type {
                    rtc::peer_connection::transport::RTCIceTcpCandidateType::Active => " tcptype active",
                    rtc::peer_connection::transport::RTCIceTcpCandidateType::Passive => " tcptype passive",
                    rtc::peer_connection::transport::RTCIceTcpCandidateType::SimultaneousOpen => " tcptype so",
                    _ => "",
                }
            } else {
                ""
            };
            let candidate_str = format!(
                "candidate:{} {} {} {} {} {} typ {}{}",
                c.foundation, c.component, c.protocol, c.priority, c.address, c.port, c.typ, tcp_suffix
            );
            let cand_c = CString::new(candidate_str).unwrap_or_default();
            cb(self.user_data, cand_c.as_ptr(), std::ptr::null());
        }
    }

    async fn on_connection_state_change(&self, state: RTCPeerConnectionState) {
        if let Some(cb) = &self.on_connection_state {
            cb(self.user_data, state as c_int);
        }
    }

    async fn on_data_channel(&self, dc: Arc<dyn webrtc::data_channel::DataChannel>) {
        // Register the remotely-received channel so its messages get polled.
        let id = dc.id();
        if let Some(cb) = &self.on_data_channel {
            let label = dc.label().await.unwrap_or_default();
            let label_c = CString::new(label).unwrap_or_default();
            self.data_channels.lock().unwrap().insert(id, dc.clone());
            spawn_data_channel_poller(dc.clone(), id, self.dc_callbacks.clone());
            cb(self.user_data, id, label_c.as_ptr());
        } else {
            self.data_channels.lock().unwrap().insert(id, dc.clone());
            spawn_data_channel_poller(dc, id, self.dc_callbacks.clone());
        }
    }

    async fn on_ice_gathering_state_change(&self, state: RTCIceGatheringState) {
        if let Some(cb) = &self.on_ice_gathering_state_change {
            let value = match state {
                RTCIceGatheringState::New => 0,
                RTCIceGatheringState::Gathering => 1,
                RTCIceGatheringState::Complete => 2,
                _ => -1,
            };
            cb(self.user_data, value);
        }
    }

    async fn on_track(&self, track: Arc<dyn TrackRemote>) {
        if let Some(cb) = &self.on_track {
            let track_id = register_track(track.clone());
            let label = track.label().await;
            let label_c = CString::new(label).unwrap_or_default();
            cb(self.user_data, track_id, label_c.as_ptr());
        }
    }
}

fn callback_is_null<T>(f: Option<T>) -> bool {
    f.is_none()
}

/// Create a peer connection. `user_data` is echoed back to every callback.
/// Returns an opaque handle (or null on failure).
#[no_mangle]
pub extern "C" fn webrtc_ffi_peer_create(
    cfg: *mut c_void,
    user_data: *mut c_void,
    on_ice_candidate: IceCandidateCallback,
    on_connection_state: ConnectionStateCallback,
    on_data_channel: DataChannelCallback,
    on_ice_gathering_state_change: IceGatheringStateCallback,
    on_track: TrackCallback,
) -> *mut c_void {
    let data_channels: Arc<Mutex<HashMap<u16, Arc<dyn webrtc::data_channel::DataChannel>>>> =
        Arc::new(Mutex::new(HashMap::new()));
    let dc_callbacks: Arc<DcCallbackMap> = Arc::new(Mutex::new(HashMap::new()));
    let handler = Forwarder {
        user_data,
        on_ice_candidate: if callback_is_null(Some(on_ice_candidate)) {
            None
        } else {
            Some(on_ice_candidate)
        },
        on_connection_state: if callback_is_null(Some(on_connection_state)) {
            None
        } else {
            Some(on_connection_state)
        },
        on_data_channel: if callback_is_null(Some(on_data_channel)) {
            None
        } else {
            Some(on_data_channel)
        },
        on_ice_gathering_state_change: if callback_is_null(Some(on_ice_gathering_state_change)) {
            None
        } else {
            Some(on_ice_gathering_state_change)
        },
        on_track: if callback_is_null(Some(on_track)) {
            None
        } else {
            Some(on_track)
        },
        data_channels: data_channels.clone(),
        dc_callbacks: dc_callbacks.clone(),
    };

    let mut builder = PeerConnectionBuilder::new().with_handler(Arc::new(handler));

    // Register the default audio/video codec set so offers/answers can be generated.
    let mut media_engine = rtc::peer_connection::configuration::media_engine::MediaEngine::default();
    if let Err(e) = media_engine.register_default_codecs() {
        eprintln!("failed to register default codecs: {e:?}");
        return std::ptr::null_mut();
    }
    builder = builder.with_media_engine(media_engine);

    // Drive the PeerConnection on an explicit tokio runtime (matches the
    // reference examples, which call `with_runtime`).
    if let Some(rt) = default_runtime() {
        builder = builder.with_runtime(rt);
    }

    // Default interceptor registry. Data channels do not require interceptors,
    // but the builder expects a `Registry<NoopInterceptor>`; the default
    // registry (no RTP/RTCP interceptors) is sufficient for data-only use.
    let registry = webrtc::peer_connection::Registry::<webrtc::peer_connection::NoopInterceptor>::new();
    builder = builder.with_interceptor_registry(registry);

    let mut setting_engine = SettingEngine::default();

    if !cfg.is_null() {
        let c = unsafe { &*(cfg as *const Config) };
        if !c.ice_servers.is_empty() {
            let configuration = RTCConfigurationBuilder::default()
                .with_ice_servers(c.ice_servers.clone())
                .build();
            builder = builder.with_configuration(configuration);
        }

        // Apply DTLS answering role if requested.
        if c.dtls_role == 1 {
            let _ = setting_engine.set_answering_dtls_role(RTCDtlsRole::Auto);
        } else if c.dtls_role == 2 {
            let _ = setting_engine.set_answering_dtls_role(RTCDtlsRole::Client);
        } else if c.dtls_role == 3 {
            let _ = setting_engine.set_answering_dtls_role(RTCDtlsRole::Server);
        }

        // Apply explicit network types (UDP/TCP) when requested.
        if c.network_types != 0 {
            let mut types = Vec::new();
            if c.network_types & 1 != 0 {
                types.push(NetworkType::Udp4);
                types.push(NetworkType::Udp6);
            }
            if c.network_types & 2 != 0 {
                types.push(NetworkType::Tcp4);
                types.push(NetworkType::Tcp6);
            }
            setting_engine.set_network_types(types);
        }

        builder = builder.with_setting_engine(setting_engine);

        if !c.tcp_addrs.is_empty() {
            builder = builder.with_tcp_addrs(c.tcp_addrs.clone());
        }
        builder = builder.with_udp_addrs(c.udp_addrs.clone());
    } else {
        builder = builder.with_udp_addrs(vec!["0.0.0.0:0".to_string()]);
    }

    let pc = match PC_RUNTIME.block_on(builder.build()) {
        Ok(pc) => Arc::new(pc) as Arc<dyn PeerConnection>,
        Err(_) => return std::ptr::null_mut(),
    };

    let peer = Box::new(Peer::new(pc, data_channels, dc_callbacks));
    Box::into_raw(peer) as *mut c_void
}

/// Close and free a peer connection handle.
#[no_mangle]
pub extern "C" fn webrtc_ffi_peer_close(peer: *mut c_void) -> c_int {
    if peer.is_null() {
        return -1;
    }
    let p = unsafe { Box::from_raw(peer as *mut Peer) };
    if let Err(_) = block_on(p.pc.close()) {
        // Still free the handle; best-effort close.
        return -2;
    }
    0
}

// ---------------------------------------------------------------------------
// SDP offer / answer
// ---------------------------------------------------------------------------

/// Create an SDP offer. `ice_restart` (non-zero) regenerates ICE credentials.
/// Returns a newly allocated description handle (free with
/// [`webrtc_ffi_description_free`]). On error returns null.
#[no_mangle]
pub extern "C" fn webrtc_ffi_create_offer(peer: *mut c_void, ice_restart: c_int) -> *mut c_void {
    if peer.is_null() {
        return std::ptr::null_mut();
    }
    let p = unsafe { &*(peer as *const Peer) };
    let options = if ice_restart != 0 {
        Some(RTCOfferOptions { ice_restart: true })
    } else {
        None
    };
    match block_on(p.pc.create_offer(options)) {
        Ok(desc) => Box::into_raw(Box::new(desc)) as *mut c_void,
        Err(_) => std::ptr::null_mut(),
    }
}

/// Create an SDP answer. Returns a newly allocated description handle or null.
#[no_mangle]
pub extern "C" fn webrtc_ffi_create_answer(peer: *mut c_void) -> *mut c_void {
    if peer.is_null() {
        return std::ptr::null_mut();
    }
    let p = unsafe { &*(peer as *const Peer) };
    match block_on(p.pc.create_answer(None)) {
        Ok(desc) => Box::into_raw(Box::new(desc)) as *mut c_void,
        Err(_) => std::ptr::null_mut(),
    }
}

/// Set the local description from a description handle. The handle is cloned and
/// remains owned by the caller (freed via `webrtc_ffi_description_free`).
#[no_mangle]
pub extern "C" fn webrtc_ffi_set_local_description(peer: *mut c_void, desc: *mut c_void) -> c_int {
    if peer.is_null() || desc.is_null() {
        return -1;
    }
    let p = unsafe { &*(peer as *const Peer) };
    let d = unsafe { &*(desc as *const RTCSessionDescription) };
    match block_on(p.pc.set_local_description(d.clone())) {
        Ok(()) => 0,
        Err(_) => -2,
    }
}

/// Set the remote description from a description handle. Consumes the handle.
#[no_mangle]
pub extern "C" fn webrtc_ffi_set_remote_description(peer: *mut c_void, desc: *mut c_void) -> c_int {
    if peer.is_null() || desc.is_null() {
        return -1;
    }
    let p = unsafe { &*(peer as *const Peer) };
    let d = unsafe { &*(desc as *const RTCSessionDescription) };
    match block_on(p.pc.set_remote_description(d.clone())) {
        Ok(()) => 0,
        Err(_) => -2,
    }
}

/// Allocate a description handle from raw SDP type (see `RtcSdpType`) and SDP text.
#[no_mangle]
pub extern "C" fn webrtc_ffi_description_create(
    sdp_type: c_int,
    sdp: *const c_char,
) -> *mut c_void {
    let sdp_text = read_str(sdp).to_string();
    let desc = match sdp_type {
        1 => RTCSessionDescription::offer(sdp_text),
        2 => RTCSessionDescription::answer(sdp_text),
        3 => RTCSessionDescription::pranswer(sdp_text),
        4 => RTCSessionDescription::rollback(Some(sdp_text)),
        _ => return std::ptr::null_mut(),
    };
    match desc {
        Ok(d) => Box::into_raw(Box::new(d)) as *mut c_void,
        Err(_) => std::ptr::null_mut(),
    }
}

/// Get the SDP text of a description handle (free with [`webrtc_ffi_free_string`]).
#[no_mangle]
pub extern "C" fn webrtc_ffi_description_sdp(desc: *mut c_void) -> *mut c_char {
    if desc.is_null() {
        return unsafe { into_cstring(String::new()) };
    }
    let d = unsafe { &*(desc as *const RTCSessionDescription) };
    unsafe { into_cstring(d.sdp.clone()) }
}

/// Get the SDP type of a description handle (see `RtcSdpType`).
#[no_mangle]
pub extern "C" fn webrtc_ffi_description_type(desc: *mut c_void) -> c_int {
    if desc.is_null() {
        return 0;
    }
    let d = unsafe { &*(desc as *const RTCSessionDescription) };
    d.sdp_type as c_int
}

/// Free a description handle.
#[no_mangle]
pub extern "C" fn webrtc_ffi_description_free(desc: *mut c_void) {
    if desc.is_null() {
        return;
    }
    unsafe {
        let _ = Box::from_raw(desc as *mut RTCSessionDescription);
    }
}

// ---------------------------------------------------------------------------
// Transceivers
// ---------------------------------------------------------------------------

/// `RtpCodecKind` values.
const CODEC_UNSPECIFIED: c_int = 0;
const CODEC_AUDIO: c_int = 1;
const CODEC_VIDEO: c_int = 2;
/// `RTCRtpTransceiverDirection` values.
const DIR_UNSPECIFIED: c_int = 0;
const DIR_SENDRECV: c_int = 1;
const DIR_SENDONLY: c_int = 2;
const DIR_RECVONLY: c_int = 3;
const DIR_INACTIVE: c_int = 4;

/// Add a transceiver of the given codec kind with the given direction.
/// Returns 0 on success, negative on error.
#[no_mangle]
pub extern "C" fn webrtc_ffi_add_transceiver(
    peer: *mut c_void,
    kind: c_int,
    direction: c_int,
) -> c_int {
    if peer.is_null() {
        return -1;
    }
    let kind = match kind {
        CODEC_AUDIO => RtpCodecKind::Audio,
        CODEC_VIDEO => RtpCodecKind::Video,
        _ => return -2,
    };
    let direction = match direction {
        DIR_SENDRECV => RTCRtpTransceiverDirection::Sendrecv,
        DIR_SENDONLY => RTCRtpTransceiverDirection::Sendonly,
        DIR_RECVONLY => RTCRtpTransceiverDirection::Recvonly,
        DIR_INACTIVE => RTCRtpTransceiverDirection::Inactive,
        _ => RTCRtpTransceiverDirection::Unspecified,
    };
    let has_send = matches!(
        direction,
        RTCRtpTransceiverDirection::Sendrecv | RTCRtpTransceiverDirection::Sendonly
    );
    let init = RTCRtpTransceiverInit {
        direction,
        streams: vec![],
        send_encodings: if has_send {
            vec![rtc::rtp_transceiver::rtp_sender::RTCRtpEncodingParameters::default()]
        } else {
            vec![]
        },
    };
    let p = unsafe { &*(peer as *const Peer) };
    match block_on(p.pc.add_transceiver_from_kind(kind, Some(init))) {
        Ok(_) => 0,
        Err(_) => -3,
    }
}

// ---------------------------------------------------------------------------
// ICE candidates
// ---------------------------------------------------------------------------

/// Add a remote ICE candidate. `candidate` is the SDP candidate attribute string,
/// `sdp_mid` is the media stream identification (may be null), `sdp_mline_index` the
/// index (pass -1 if unknown).
#[no_mangle]
pub extern "C" fn webrtc_ffi_add_ice_candidate(
    peer: *mut c_void,
    candidate: *const c_char,
    sdp_mid: *const c_char,
    sdp_mline_index: c_int,
) -> c_int {
    if peer.is_null() {
        return -1;
    }
    let init = RTCIceCandidateInit {
        candidate: read_str(candidate).to_string(),
        sdp_mid: if read_str(sdp_mid).is_empty() {
            None
        } else {
            Some(read_str(sdp_mid).to_string())
        },
        sdp_mline_index: if sdp_mline_index < 0 {
            None
        } else {
            Some(sdp_mline_index as u16)
        },
        username_fragment: None,
        url: None,
    };
    let p = unsafe { &*(peer as *const Peer) };
    // This function is typically invoked from an ICE candidate callback that runs
    // on a `PC_RUNTIME` worker thread, so we must not `block_on` here (that would
    // panic with "runtime within a runtime"). Spawn the async work onto the PC
    // runtime and return immediately.
    let pc = p.pc.clone();
    PC_RUNTIME.spawn(async move {
        let _ = pc.add_ice_candidate(init).await;
    });
    0
}

// ---------------------------------------------------------------------------
// Data channels
// ---------------------------------------------------------------------------

/// `RTCDataChannelState` values.
const DC_STATE_CONNECTING: c_int = 0;
const DC_STATE_OPEN: c_int = 1;
const DC_STATE_CLOSING: c_int = 2;
const DC_STATE_CLOSED: c_int = 3;

/// Create a data channel. Returns a handle id (u16) >= 0 on success, negative on error.
#[no_mangle]
pub extern "C" fn webrtc_ffi_create_data_channel(
    peer: *mut c_void,
    label: *const c_char,
    ordered: bool,
) -> c_int {
    if peer.is_null() {
        return -1;
    }
    let opts = RTCDataChannelInit {
        ordered,
        ..Default::default()
    };
    let p = unsafe { &*(peer as *const Peer) };
    let dc = match block_on(p.pc.create_data_channel(read_str(label), Some(opts))) {
        Ok(dc) => dc,
        Err(_) => return -2,
    };
    let id = dc.id();
    p.data_channels.lock().unwrap().insert(id, dc.clone());
    spawn_data_channel_poller(dc, id, p.dc_callbacks.clone());
    id as c_int
}

/// Register callbacks for a data channel (by id) on a specific peer.
#[no_mangle]
pub extern "C" fn webrtc_ffi_data_channel_set_callbacks(
    peer: *mut c_void,
    id: u16,
    on_message: Option<extern "C" fn(u16, *const u8, usize)>,
    on_open: Option<extern "C" fn(u16)>,
    on_close: Option<extern "C" fn(u16)>,
) {
    if peer.is_null() {
        return;
    }
    let p = unsafe { &*(peer as *const Peer) };
    p.dc_callbacks.lock().unwrap().insert(
        id,
        DataChannelCallbacks {
            on_message,
            on_open,
            on_close,
        },
    );
}

/// Send UTF-8 text on a data channel. Returns 0 on success.
#[no_mangle]
pub extern "C" fn webrtc_ffi_data_channel_send_text(
    peer: *mut c_void,
    id: u16,
    text: *const c_char,
) -> c_int {
    if peer.is_null() {
        return -1;
    }
    let p = unsafe { &*(peer as *const Peer) };
    let dc = match p.data_channels.lock().unwrap().get(&id).cloned() {
        Some(dc) => dc,
        None => return -2,
    };
    // Read the string into an owned value before spawning (raw pointers are not Send).
    let message = read_str(text).to_string();
    // May be called from a data-channel poller callback running on a PC_RUNTIME
    // worker, so spawn instead of blocking.
    PC_RUNTIME.spawn(async move {
        let _ = dc.send_text(message.as_str()).await;
    });
    0
}

/// Send raw bytes on a data channel. Returns 0 on success.
#[no_mangle]
pub extern "C" fn webrtc_ffi_data_channel_send_bytes(
    peer: *mut c_void,
    id: u16,
    data: *const u8,
    len: usize,
) -> c_int {
    if peer.is_null() || data.is_null() {
        return -1;
    }
    let bytes = unsafe { std::slice::from_raw_parts(data, len) };
    let p = unsafe { &*(peer as *const Peer) };
    let dc = match p.data_channels.lock().unwrap().get(&id).cloned() {
        Some(dc) => dc,
        None => return -2,
    };
    let mut buf = bytes::BytesMut::with_capacity(len);
    buf.extend_from_slice(bytes);
    PC_RUNTIME.spawn(async move {
        let _ = dc.send(buf).await;
    });
    0
}

/// Poll a data channel for events on a background task and forward to callbacks.
fn spawn_data_channel_poller(
    dc: Arc<dyn webrtc::data_channel::DataChannel>,
    id: u16,
    callbacks: Arc<DcCallbackMap>,
) {
    PC_RUNTIME.spawn(async move {
        let mut opened = false;
        loop {
            match dc.poll().await {
                Some(DataChannelEvent::OnOpen) => {
                    opened = true;
                    if let Some(cb) =
                        callbacks.lock().unwrap().get(&id).and_then(|c| c.on_open)
                    {
                        cb(id);
                    }
                }
                Some(DataChannelEvent::OnMessage(msg)) => {
                    if !opened {
                        opened = true;
                        if let Some(cb) =
                            callbacks.lock().unwrap().get(&id).and_then(|c| c.on_open)
                        {
                            cb(id);
                        }
                    }
                    if let Some(cb) =
                        callbacks.lock().unwrap().get(&id).and_then(|c| c.on_message)
                    {
                        let payload: &[u8] = msg.data.as_ref();
                        cb(id, payload.as_ptr(), payload.len());
                    }
                }
                Some(DataChannelEvent::OnClose) | None => {
                    if let Some(cb) =
                        callbacks.lock().unwrap().get(&id).and_then(|c| c.on_close)
                    {
                        cb(id);
                    }
                    break;
                }
                // OnError / OnClosing / OnBufferedAmountLow / OnBufferedAmountHigh:
                // keep polling; these do not terminate the channel.
                _ => {}
            }
        }
        callbacks.lock().unwrap().remove(&id);
    });
}

// ---------------------------------------------------------------------------
// Codec enumeration
// ---------------------------------------------------------------------------

/// Return the full list of supported codecs as a NUL-terminated string.
///
/// The returned string is owned by the caller and must be freed with
/// [`webrtc_ffi_free_string`]. Each codec is on its own line, with fields
/// separated by horizontal tabs:
///
/// ```text
/// <kind>\t<mime_type>\t<clock_rate>\t<channels>\t<payload_type>\t<fmtp>
/// ```
///
/// `<kind>` is `audio` or `video`. `<channels>` is `0` for video codecs.
#[no_mangle]
pub extern "C" fn webrtc_ffi_supported_codecs() -> *mut c_char {
    let mut me = rtc::peer_connection::configuration::media_engine::MediaEngine::default();
    if me.register_default_codecs().is_err() {
        return unsafe { into_cstring(String::new()) };
    }

    let mut out = String::new();
    for (kind_str, codecs) in [
        ("audio", me.registered_audio_codecs()),
        ("video", me.registered_video_codecs()),
    ] {
        for c in codecs {
            let channels = if c.rtp_codec.channels == 0 {
                String::new()
            } else {
                c.rtp_codec.channels.to_string()
            };
            out.push_str(&format!(
                "{}\t{}\t{}\t{}\t{}\t{}\n",
                kind_str,
                c.rtp_codec.mime_type,
                c.rtp_codec.clock_rate,
                channels,
                c.payload_type,
                c.rtp_codec.sdp_fmtp_line,
            ));
        }
    }
    unsafe { into_cstring(out) }
}

// ---------------------------------------------------------------------------
// Track handles + on_track callback
// ---------------------------------------------------------------------------

/// Global registry of remote tracks keyed by a monotonically increasing id.
static TRACK_ID_COUNTER: Lazy<Mutex<u32>> = Lazy::new(|| Mutex::new(1));
static REMOTE_TRACKS: Lazy<Mutex<HashMap<u32, Arc<dyn TrackRemote>>>> =
    Lazy::new(|| Mutex::new(HashMap::new()));

/// Per-track event callbacks. Keyed by the same id as `REMOTE_TRACKS`.
type TrackEventCallbackMap = Mutex<HashMap<u32, TrackEventCallbacks>>;

#[derive(Clone, Copy)]
struct TrackEventCallbacks {
    on_rtp: Option<extern "C" fn(u32, *const u8, usize, u8, u16, u32, u32)>,
    on_open: Option<extern "C" fn(u32, u32, *const c_char)>,
}

static TRACK_EVENT_CALLBACKS: Lazy<TrackEventCallbackMap> = Lazy::new(|| Mutex::new(HashMap::new()));

/// Allocate a new id and register a remote track.
fn register_track(track: Arc<dyn TrackRemote>) -> u32 {
    let mut counter = TRACK_ID_COUNTER.lock().unwrap();
    let id = *counter;
    *counter += 1;
    REMOTE_TRACKS.lock().unwrap().insert(id, track);
    id
}

/// Spawn a poller that reads events from a remote track and forwards them
/// to Java via the registered callbacks.
fn spawn_track_poller(track: Arc<dyn TrackRemote>, id: u32) {
    PC_RUNTIME.spawn(async move {
        loop {
            match track.poll().await {
                Some(TrackRemoteEvent::OnOpen(init)) => {
                    let ssrc = init.ssrc;
                    let rid = init.rid.unwrap_or_default();
                    let rid_c = CString::new(rid).unwrap_or_default();
                    if let Some(cb) = TRACK_EVENT_CALLBACKS.lock().unwrap().get(&id).and_then(|c| c.on_open) {
                        cb(id, ssrc, rid_c.as_ptr());
                    }
                }
                Some(TrackRemoteEvent::OnRtpPacket(pkt)) => {
                    let header = &pkt.header;
                    let payload: &[u8] = pkt.payload.as_ref();
                    if let Some(cb) = TRACK_EVENT_CALLBACKS.lock().unwrap().get(&id).and_then(|c| c.on_rtp) {
                        cb(
                            id,
                            payload.as_ptr(),
                            payload.len(),
                            header.payload_type,
                            header.sequence_number,
                            header.timestamp,
                            header.ssrc,
                        );
                    }
                }
                Some(TrackRemoteEvent::OnEnding)
                | Some(TrackRemoteEvent::OnEnded)
                | Some(TrackRemoteEvent::OnError)
                | None => {
                    break;
                }
                _ => {}
            }
        }
        REMOTE_TRACKS.lock().unwrap().remove(&id);
        TRACK_EVENT_CALLBACKS.lock().unwrap().remove(&id);
    });
}

/// Get a remote track handle by id.
fn with_track<T, F: FnOnce(&Arc<dyn TrackRemote>) -> T>(id: u32, f: F) -> Option<T> {
    REMOTE_TRACKS.lock().unwrap().get(&id).map(|t| f(t))
}

// ---------------------------------------------------------------------------
// Transceiver / Stats / Track FFI functions
// ---------------------------------------------------------------------------

/// Add a recvonly or sendrecv transceiver of the given kind.
/// `kind`: 0=audio, 1=video. `direction`: 0=recvonly, 3=sendrecv.
/// Returns 0 on success.
#[no_mangle]
pub extern "C" fn webrtc_ffi_add_transceiver_from_kind(
    peer: *mut c_void,
    kind: c_int,
    direction: c_int,
) -> c_int {
    if peer.is_null() {
        return -1;
    }
    let p = unsafe { &*(peer as *const Peer) };
    let codec_kind = match kind {
        0 => RtpCodecKind::Audio,
        1 => RtpCodecKind::Video,
        _ => return -2,
    };
    let dir = match direction {
        DIR_SENDRECV => RTCRtpTransceiverDirection::Sendrecv,
        DIR_SENDONLY => RTCRtpTransceiverDirection::Sendonly,
        DIR_RECVONLY => RTCRtpTransceiverDirection::Recvonly,
        DIR_INACTIVE => RTCRtpTransceiverDirection::Inactive,
        _ => RTCRtpTransceiverDirection::Unspecified,
    };
    let init = RTCRtpTransceiverInit {
        direction: dir,
        ..Default::default()
    };
    let result = block_on(p.pc.add_transceiver_from_kind(codec_kind, Some(init)));
    match result {
        Ok(_) => 0,
        Err(_) => -4,
    }
}

/// Get inbound RTP stream stats as a JSON string. Returns a NUL-terminated
/// C string the caller must free with `webrtc_ffi_free_string`.
#[no_mangle]
pub extern "C" fn webrtc_ffi_get_stats(peer: *mut c_void) -> *mut c_char {
    if peer.is_null() {
        return unsafe { into_cstring(String::new()) };
    }
    let p = unsafe { &*(peer as *const Peer) };
    let report = block_on(p.pc.get_stats(
        std::time::Instant::now(),
        rtc::statistics::StatsSelector::None,
    ));
    let mut out = String::new();
    out.push('[');
    let mut first = true;
    for entry in report.iter() {
        if !first {
            out.push(',');
        }
        first = false;
        match entry {
            RTCStatsReportEntry::PeerConnection(s) => {
                out.push_str(&format!(
                    "{{\"type\":\"peer-connection\",\"data_channels_opened\":{},\"data_channels_closed\":{}}}",
                    s.data_channels_opened, s.data_channels_closed
                ));
            }
            RTCStatsReportEntry::InboundRtp(s) => {
                let base = &s.received_rtp_stream_stats;
                out.push_str(&format!(
                    "{{\"type\":\"inbound-rtp\",\"ssrc\":{},\"kind\":\"{}\",\"track_identifier\":\"{}\",\"bytes_received\":{},\"packets_received\":{},\"packets_lost\":{},\"jitter\":{},\"nack_count\":{},\"fir_count\":{},\"pli_count\":{}}}",
                    base.rtp_stream_stats.ssrc,
                    base.rtp_stream_stats.kind,
                    s.track_identifier,
                    s.bytes_received,
                    base.packets_received,
                    base.packets_lost,
                    base.jitter,
                    s.nack_count,
                    s.fir_count,
                    s.pli_count,
                ));
            }
            RTCStatsReportEntry::RemoteCandidate(s) => {
                let addr = s.address.as_deref().unwrap_or("");
                out.push_str(&format!(
                    "{{\"type\":\"remote-candidate\",\"address\":\"{}\",\"port\":{},\"candidate_type\":\"{:?}\"}}",
                    addr, s.port, s.candidate_type
                ));
            }
            _ => {}
        }
    }
    out.push(']');
    unsafe { into_cstring(out) }
}

/// Get the SSRCs of a remote track as a space-separated string.
/// Returns a NUL-terminated C string the caller must free.
#[no_mangle]
pub extern "C" fn webrtc_ffi_track_remote_ssrcs(track_id: u32) -> *mut c_char {
    let result = with_track(track_id, |t| {
        let ssrcs = block_on(t.ssrcs());
        ssrcs.iter()
            .map(|s| s.to_string())
            .collect::<Vec<_>>()
            .join(" ")
    });
    unsafe { into_cstring(result.unwrap_or_default()) }
}

/// Get the codec info of a remote track as a tab-separated string:
/// `mime_type\tpayload_type\tclock_rate\tchannels\tsdp_fmtp_line`
#[no_mangle]
pub extern "C" fn webrtc_ffi_track_remote_codec(track_id: u32, ssrc: u32) -> *mut c_char {
    let result = with_track(track_id, |t| {
        block_on(t.codec(ssrc)).map(|c| {
            format!(
                "{}\t{}\t{}\t{}",
                c.mime_type, c.clock_rate, c.channels, c.sdp_fmtp_line
            )
        })
    });
    unsafe { into_cstring(result.flatten().unwrap_or_default()) }
}

/// Get the kind of a remote track: 0=audio, 1=video, -1=error.
#[no_mangle]
pub extern "C" fn webrtc_ffi_track_remote_kind(track_id: u32) -> c_int {
    with_track(track_id, |t| {
        match block_on(t.kind()) {
            RtpCodecKind::Audio => 0,
            RtpCodecKind::Video => 1,
            _ => -1,
        }
    }).unwrap_or(-1)
}

/// Get the RID of a remote track for a given SSRC. Returns NUL-terminated
/// C string (empty if no RID).
#[no_mangle]
pub extern "C" fn webrtc_ffi_track_remote_rid(track_id: u32, ssrc: u32) -> *mut c_char {
    let result = with_track(track_id, |t| {
        block_on(t.rid(ssrc)).unwrap_or_default()
    });
    unsafe { into_cstring(result.unwrap_or_default()) }
}

/// Send a PictureLossIndication (PLI) RTCP packet to a remote track.
/// `media_ssrc` is the SSRC of the media stream.
#[no_mangle]
pub extern "C" fn webrtc_ffi_track_remote_write_rtcp(track_id: u32, media_ssrc: u32) -> c_int {
    with_track(track_id, |t| {
        let pli = PictureLossIndication {
            sender_ssrc: 0,
            media_ssrc,
        };
        block_on(t.write_rtcp(vec![Box::new(pli)]))
    })
    .unwrap_or(Err(webrtc::error::Error::ErrBufferFull))
    .is_ok() as c_int
}

/// Register event callbacks for a remote track. The poller will be spawned
/// automatically when the track is received via `on_track`.
#[no_mangle]
pub extern "C" fn webrtc_ffi_track_remote_set_callbacks(
    track_id: u32,
    on_rtp: Option<extern "C" fn(u32, *const u8, usize, u8, u16, u32, u32)>,
    on_open: Option<extern "C" fn(u32, u32, *const c_char)>,
) {
    TRACK_EVENT_CALLBACKS.lock().unwrap().insert(
        track_id,
        TrackEventCallbacks { on_rtp, on_open },
    );
    // If the track is already registered, spawn a poller now.
    if let Some(track) = REMOTE_TRACKS.lock().unwrap().get(&track_id).cloned() {
        spawn_track_poller(track, track_id);
    }
}

/// Get the track ID of a remote track. Returns a NUL-terminated C string.
#[no_mangle]
pub extern "C" fn webrtc_ffi_track_remote_id(track_id: u32) -> *mut c_char {
    let result = with_track(track_id, |t| {
        block_on(t.track_id())
    });
    unsafe { into_cstring(result.unwrap_or_default()) }
}

/// Get the label of a remote track. Returns a NUL-terminated C string.
#[no_mangle]
pub extern "C" fn webrtc_ffi_track_remote_label(track_id: u32) -> *mut c_char {
    let result = with_track(track_id, |t| {
        block_on(t.label())
    });
    unsafe { into_cstring(result.unwrap_or_default()) }
}

// ---------------------------------------------------------------------------
// Local track (TrackLocalStaticSample) + Sender management
// ---------------------------------------------------------------------------

static LOCAL_TRACK_ID_COUNTER: Lazy<Mutex<u32>> = Lazy::new(|| Mutex::new(1));
static LOCAL_TRACKS: Lazy<Mutex<HashMap<u32, Arc<TrackLocalStaticSample>>>> =
    Lazy::new(|| Mutex::new(HashMap::new()));
static SENDER_ID_COUNTER: Lazy<Mutex<u32>> = Lazy::new(|| Mutex::new(1));
static SENDERS: Lazy<Mutex<HashMap<u32, Arc<dyn RtpSender>>>> =
    Lazy::new(|| Mutex::new(HashMap::new()));

fn register_local_track(track: Arc<TrackLocalStaticSample>) -> u32 {
    let mut counter = LOCAL_TRACK_ID_COUNTER.lock().unwrap();
    let id = *counter;
    *counter += 1;
    LOCAL_TRACKS.lock().unwrap().insert(id, track);
    id
}

fn register_sender(sender: Arc<dyn RtpSender>) -> u32 {
    let mut counter = SENDER_ID_COUNTER.lock().unwrap();
    let id = *counter;
    *counter += 1;
    SENDERS.lock().unwrap().insert(id, sender);
    id
}

/// Create a local track for sending media samples. Returns a track handle id
/// (u32) on success, 0 on failure.
///
/// - `stream_id`, `track_id`, `label`: metadata strings
/// - `kind`: 1=audio, 2=video
/// - `ssrc`: synchronization source identifier
/// - `mime_type`: e.g. "audio/opus", "video/H264", "video/VP8"
/// - `clock_rate`: e.g. 48000 for Opus, 90000 for video
/// - `channels`: 0 for video, 2 for stereo Opus
/// - `sdp_fmtp_line`: codec-specific SDP fmtp, may be empty
#[no_mangle]
pub extern "C" fn webrtc_ffi_create_track_local(
    stream_id: *const c_char,
    track_id: *const c_char,
    label: *const c_char,
    kind: c_int,
    ssrc: u32,
    mime_type: *const c_char,
    clock_rate: u32,
    channels: c_int,
    sdp_fmtp_line: *const c_char,
) -> u32 {
    let codec_kind = match kind {
        1 => RtpCodecKind::Audio,
        2 => RtpCodecKind::Video,
        _ => return 0,
    };
    let codec = RTCRtpCodec {
        mime_type: read_str(mime_type).to_string(),
        clock_rate,
        channels: channels as u16,
        sdp_fmtp_line: read_str(sdp_fmtp_line).to_string(),
        rtcp_feedback: vec![],
    };
    let encoding = RTCRtpEncodingParameters {
        rtp_coding_parameters: RTCRtpCodingParameters {
            ssrc: Some(ssrc),
            ..Default::default()
        },
        codec,
        ..Default::default()
    };
    let track_metadata = MediaStreamTrack::new(
        read_str(stream_id).to_string(),
        read_str(track_id).to_string(),
        read_str(label).to_string(),
        codec_kind,
        vec![encoding],
    );
    let track = match TrackLocalStaticSample::new(track_metadata) {
        Ok(t) => t,
        Err(_) => return 0,
    };
    register_local_track(Arc::new(track))
}

/// Add a local track to a peer connection. Returns a sender handle id (>= 1)
/// on success, negative on error.
#[no_mangle]
pub extern "C" fn webrtc_ffi_add_track(peer: *mut c_void, track_id: u32) -> c_int {
    if peer.is_null() {
        return -1;
    }
    let track = LOCAL_TRACKS.lock().unwrap().get(&track_id).cloned();
    let track = match track {
        Some(t) => t,
        None => return -2,
    };
    let p = unsafe { &*(peer as *const Peer) };
    match block_on(p.pc.add_track(track as Arc<dyn TrackLocal>)) {
        Ok(sender) => register_sender(sender) as c_int,
        Err(_) => -3,
    }
}

/// Write a media sample to a local track. The data is the raw codec bitstream
/// (e.g. NAL unit for H.264, IVF frame for VP8, Opus packet).
///
/// - `track_id`: handle from `webrtc_ffi_create_track_local`
/// - `ssrc`: must match the SSRC used at track creation
/// - `payload_type`: negotiated payload type from `webrtc_ffi_sender_get_payload_type`
/// - `data` / `len`: raw codec frame bytes
/// - `duration_ms`: sample duration in milliseconds
#[no_mangle]
pub extern "C" fn webrtc_ffi_write_sample(
    track_id: u32,
    ssrc: u32,
    payload_type: u8,
    data: *const u8,
    len: usize,
    duration_ms: u32,
) -> c_int {
    if data.is_null() || len == 0 {
        return -1;
    }
    let track = LOCAL_TRACKS.lock().unwrap().get(&track_id).cloned();
    let track = match track {
        Some(t) => t,
        None => return -2,
    };
    let bytes = unsafe { std::slice::from_raw_parts(data, len) };
    let mut buf = bytes::BytesMut::with_capacity(len);
    buf.extend_from_slice(bytes);
    let sample = Sample {
        data: buf.freeze(),
        duration: std::time::Duration::from_millis(duration_ms as u64),
        ..Default::default()
    };
    match block_on(track.write_sample(ssrc, payload_type, &sample, &[])) {
        Ok(()) => 0,
        Err(_) => -3,
    }
}

/// Get sender handle IDs for a peer connection as a space-separated string.
/// Returns a NUL-terminated C string the caller must free.
#[no_mangle]
pub extern "C" fn webrtc_ffi_get_senders(peer: *mut c_void) -> *mut c_char {
    if peer.is_null() {
        return unsafe { into_cstring(String::new()) };
    }
    let p = unsafe { &*(peer as *const Peer) };
    let senders = block_on(p.pc.get_senders());
    let ids: Vec<String> = senders
        .iter()
        .map(|s| register_sender(s.clone()).to_string())
        .collect();
    unsafe { into_cstring(ids.join(" ")) }
}

/// Remove a local track from a peer connection by sender handle id.
/// Returns 0 on success, negative on error.
#[no_mangle]
pub extern "C" fn webrtc_ffi_remove_track(peer: *mut c_void, sender_id: u32) -> c_int {
    if peer.is_null() {
        return -1;
    }
    let sender = SENDERS.lock().unwrap().get(&sender_id).cloned();
    let sender = match sender {
        Some(s) => s,
        None => return -2,
    };
    let p = unsafe { &*(peer as *const Peer) };
    match block_on(p.pc.remove_track(&sender)) {
        Ok(()) => 0,
        Err(_) => -3,
    }
}

/// Get the negotiated payload type for a sender. Returns the PT (0-255) or
/// -1 on error.
#[no_mangle]
pub extern "C" fn webrtc_ffi_sender_get_payload_type(sender_id: u32) -> c_int {
    let sender = SENDERS.lock().unwrap().get(&sender_id).cloned();
    let sender = match sender {
        Some(s) => s,
        None => return -1,
    };
    match block_on(sender.get_parameters()) {
        Ok(params) => params
            .rtp_parameters
            .codecs
            .first()
            .map(|c| c.payload_type as c_int)
            .unwrap_or(-1),
        Err(_) => -1,
    }
}

/// Get the negotiated codec info for a sender as a tab-separated string:
/// `mime_type\tpayload_type\tclock_rate\tchannels\tsdp_fmtp_line`
/// Returns a NUL-terminated C string the caller must free.
#[no_mangle]
pub extern "C" fn webrtc_ffi_sender_get_codec(sender_id: u32) -> *mut c_char {
    let sender = SENDERS.lock().unwrap().get(&sender_id).cloned();
    let sender = match sender {
        Some(s) => s,
        None => return unsafe { into_cstring(String::new()) },
    };
    let result = block_on(sender.get_parameters()).ok().and_then(|params| {
        params.rtp_parameters.codecs.first().map(|c| {
            format!(
                "{}\t{}\t{}\t{}\t{}",
                c.rtp_codec.mime_type,
                c.payload_type,
                c.rtp_codec.clock_rate,
                c.rtp_codec.channels,
                c.rtp_codec.sdp_fmtp_line,
            )
        })
    });
    unsafe { into_cstring(result.unwrap_or_default()) }
}
