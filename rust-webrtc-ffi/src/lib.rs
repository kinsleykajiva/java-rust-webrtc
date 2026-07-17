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
use webrtc::data_channel::{DataChannel as _, DataChannelEvent, RTCDataChannelInit};

use rtc::data_channel::{RTCDataChannelId, RTCDataChannelMessage, RTCDataChannelState};
use rtc::peer_connection::configuration::RTCIceServer;
use rtc::peer_connection::sdp::{RTCSdpType, RTCSessionDescription};
use rtc::peer_connection::state::RTCPeerConnectionState;
use rtc::peer_connection::transport::{RTCIceCandidateInit};
use rtc::rtp_transceiver::rtp_sender::{RtpCodecKind, RTCRtpEncodingParameters};
use rtc::rtp_transceiver::{RTCRtpTransceiverDirection, RTCRtpTransceiverInit};

/// Per-process async runtime used to drive every async PeerConnection operation.
static RUNTIME: Lazy<Runtime> = Lazy::new(|| {
    tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .expect("failed to build tokio runtime")
});

fn block_on<F: std::future::Future>(fut: F) -> F::Output {
    RUNTIME.block_on(fut)
}

// ---------------------------------------------------------------------------
// Opaque handle types
// ---------------------------------------------------------------------------

/// A builder-side configuration accumulated before constructing a peer connection.
struct Config {
    ice_servers: Vec<RTCIceServer>,
}

/// Live peer connection plus its event forwarder and open data channels.
struct Peer {
    pc: Arc<dyn PeerConnection>,
    /// Data channels created locally or received remotely, keyed by id.
    data_channels: Mutex<HashMap<u16, Arc<dyn webrtc::data_channel::DataChannel>>>,
}

impl Peer {
    fn new(pc: Arc<dyn PeerConnection>) -> Self {
        Self {
            pc,
            data_channels: Mutex::new(HashMap::new()),
        }
    }
}

/// Registry of active data-channel message callbacks keyed by channel id.
static DATA_CHANNEL_CALLBACKS: Lazy<Mutex<HashMap<u16, DataChannelCallbacks>>> =
    Lazy::new(|| Mutex::new(HashMap::new()));

#[derive(Clone, Copy)]
struct DataChannelCallbacks {
    on_message: Option<extern "C" fn(u16, *const u8, usize)>,
    on_open: Option<extern "C" fn(u16)>,
    on_close: Option<extern "C" fn(u16)>,
}

// ---------------------------------------------------------------------------
// C callback function-pointer types (Java supplies these via jextract downcalls)
// ---------------------------------------------------------------------------

/// ICE candidate event. `candidate` is the SDP candidate string; `sdp_mid` may be null.
type IceCandidateCallback = extern "C" fn(*mut c_void, *const c_char, *const c_char);
/// Connection state change (see `RtcPeerConnectionState` enum values).
type ConnectionStateCallback = extern "C" fn(*mut c_void, c_int);
/// Data channel created by the remote peer; surfaced by id + label.
type DataChannelCallback = extern "C" fn(*mut c_void, u16, *const c_char);

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
    });
    Box::into_raw(cfg) as *mut c_void
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
            let candidate_str = format!(
                "candidate:{} {} {} {} {} {} typ {}",
                c.foundation, c.component, c.protocol, c.priority, c.address, c.port, c.typ
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
        if let Some(cb) = &self.on_data_channel {
            let id = dc.id();
            let label = dc.label().await.unwrap_or_default();
            let label_c = CString::new(label).unwrap_or_default();
            cb(self.user_data, id, label_c.as_ptr());
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
) -> *mut c_void {
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
    };

    let mut builder = PeerConnectionBuilder::new().with_handler(Arc::new(handler));

    // Register the default audio/video codec set so offers/answers can be generated.
    let mut media_engine = rtc::peer_connection::configuration::media_engine::MediaEngine::default();
    if let Err(e) = media_engine.register_default_codecs() {
        eprintln!("failed to register default codecs: {e:?}");
        return std::ptr::null_mut();
    }
    builder = builder.with_media_engine(media_engine);

    if !cfg.is_null() {
        let c = unsafe { &*(cfg as *const Config) };
        if !c.ice_servers.is_empty() {
            let configuration = RTCConfigurationBuilder::default()
                .with_ice_servers(c.ice_servers.clone())
                .build();
            builder = builder.with_configuration(configuration);
        }
    }

    let pc = match block_on(builder.with_udp_addrs(vec!["0.0.0.0:0"]).build()) {
        Ok(pc) => Arc::new(pc) as Arc<dyn PeerConnection>,
        Err(_) => return std::ptr::null_mut(),
    };

    let peer = Box::new(Peer::new(pc));
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

/// Create an SDP offer. Returns a newly allocated description handle
/// (free with [`webrtc_ffi_description_free`]). On error returns null.
#[no_mangle]
pub extern "C" fn webrtc_ffi_create_offer(peer: *mut c_void) -> *mut c_void {
    if peer.is_null() {
        return std::ptr::null_mut();
    }
    let p = unsafe { &*(peer as *const Peer) };
    match block_on(p.pc.create_offer(None)) {
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
    match block_on(p.pc.add_ice_candidate(init)) {
        Ok(()) => 0,
        Err(_) => -2,
    }
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
    spawn_data_channel_poller(dc, id);
    id as c_int
}

/// Register callbacks for a data channel (by id).
#[no_mangle]
pub extern "C" fn webrtc_ffi_data_channel_set_callbacks(
    id: u16,
    on_message: Option<extern "C" fn(u16, *const u8, usize)>,
    on_open: Option<extern "C" fn(u16)>,
    on_close: Option<extern "C" fn(u16)>,
) {
    DATA_CHANNEL_CALLBACKS.lock().unwrap().insert(
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
    match block_on(dc.send_text(read_str(text))) {
        Ok(()) => 0,
        Err(_) => -3,
    }
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
    match block_on(dc.send(buf)) {
        Ok(()) => 0,
        Err(_) => -3,
    }
}

/// Poll a data channel for events on a background task and forward to callbacks.
fn spawn_data_channel_poller(dc: Arc<dyn webrtc::data_channel::DataChannel>, id: u16) {
    RUNTIME.spawn(async move {
        let mut opened = false;
        loop {
            match dc.poll().await {
                Some(DataChannelEvent::OnOpen) => {
                    opened = true;
                    if let Some(cb) =
                        DATA_CHANNEL_CALLBACKS.lock().unwrap().get(&id).and_then(|c| c.on_open)
                    {
                        cb(id);
                    }
                }
                Some(DataChannelEvent::OnMessage(msg)) => {
                    if !opened {
                        opened = true;
                        if let Some(cb) =
                            DATA_CHANNEL_CALLBACKS.lock().unwrap().get(&id).and_then(|c| c.on_open)
                        {
                            cb(id);
                        }
                    }
                    if let Some(cb) =
                        DATA_CHANNEL_CALLBACKS.lock().unwrap().get(&id).and_then(|c| c.on_message)
                    {
                        let payload: &[u8] = msg.data.as_ref();
                        cb(id, payload.as_ptr(), payload.len());
                    }
                }
                Some(DataChannelEvent::OnClose) => {
                    if let Some(cb) =
                        DATA_CHANNEL_CALLBACKS.lock().unwrap().get(&id).and_then(|c| c.on_close)
                    {
                        cb(id);
                    }
                    break;
                }
                _ => break,
            }
        }
        DATA_CHANNEL_CALLBACKS.lock().unwrap().remove(&id);
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
