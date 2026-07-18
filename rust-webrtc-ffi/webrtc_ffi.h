#ifndef WEBRTC_FFI_H
#define WEBRTC_FFI_H

#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>

/**
 * ICE candidate event. `candidate` is the SDP candidate string; `sdp_mid` may be null.
 */
typedef void (*IceCandidateCallback)(void*, const char*, const char*);

/**
 * Connection state change (see `RtcPeerConnectionState` enum values).
 */
typedef void (*ConnectionStateCallback)(void*, int);

/**
 * Data channel created by the remote peer; surfaced by id + label.
 */
typedef void (*DataChannelCallback)(void*, uint16_t, const char*);

/**
 * ICE gathering state change (0=new, 1=gathering, 2=complete).
 */
typedef void (*IceGatheringStateCallback)(void*, int);

/**
 * Remote track received; surfaced by track_id + label.
 */
typedef void (*TrackCallback)(void*, uint32_t, const char*);

/**
 * Returns a status string confirming the FFI bridge is loaded.
 */
char *webrtc_ffi_init(void);

/**
 * Free a string previously returned by the FFI.
 */
void webrtc_ffi_free_string(char *s);

/**
 * Create an empty configuration handle.
 */
void *webrtc_ffi_config_create(void);

/**
 * Configure transport addresses and DTLS/network options.
 *
 * - `udp_addrs` / `tcp_addrs`: space/comma separated listen addresses. An empty
 *   string leaves the default (UDP `0.0.0.0:0`); pass an explicit empty list to
 *   disable that transport (e.g. empty UDP + a TCP addr = TCP only).
 * - `dtls_role`: 0 unspecified, 1 auto, 2 client, 3 server.
 * - `network_types`: bitmask, bit0=udp, bit1=tcp (0 = library defaults).
 */
int webrtc_ffi_config_set_transport(void *cfg,
                                    const char *udp_addrs,
                                    const char *tcp_addrs,
                                    int dtls_role,
                                    int network_types);

/**
 * Add a STUN/TURN server (urls separated by commas/spaces, optional user & credential).
 */
int webrtc_ffi_config_add_ice_server(void *cfg,
                                     const char *urls,
                                     const char *username,
                                     const char *credential);

/**
 * Free a configuration handle.
 */
void webrtc_ffi_config_free(void *cfg);

/**
 * Create a peer connection. `user_data` is echoed back to every callback.
 * Returns an opaque handle (or null on failure).
 */
void *webrtc_ffi_peer_create(void *cfg,
                             void *user_data,
                             IceCandidateCallback on_ice_candidate,
                             ConnectionStateCallback on_connection_state,
                             DataChannelCallback on_data_channel,
                             IceGatheringStateCallback on_ice_gathering_state_change,
                             TrackCallback on_track);

/**
 * Close and free a peer connection handle.
 */
int webrtc_ffi_peer_close(void *peer);

/**
 * Create an SDP offer. `ice_restart` (non-zero) regenerates ICE credentials.
 * Returns a newly allocated description handle (free with
 * [`webrtc_ffi_description_free`]). On error returns null.
 */
void *webrtc_ffi_create_offer(void *peer, int ice_restart);

/**
 * Create an SDP answer. Returns a newly allocated description handle or null.
 */
void *webrtc_ffi_create_answer(void *peer);

/**
 * Set the local description from a description handle. The handle is cloned and
 * remains owned by the caller (freed via `webrtc_ffi_description_free`).
 */
int webrtc_ffi_set_local_description(void *peer, void *desc);

/**
 * Set the remote description from a description handle. Consumes the handle.
 */
int webrtc_ffi_set_remote_description(void *peer, void *desc);

/**
 * Allocate a description handle from raw SDP type (see `RtcSdpType`) and SDP text.
 */
void *webrtc_ffi_description_create(int sdp_type, const char *sdp);

/**
 * Get the SDP text of a description handle (free with [`webrtc_ffi_free_string`]).
 */
char *webrtc_ffi_description_sdp(void *desc);

/**
 * Get the SDP type of a description handle (see `RtcSdpType`).
 */
int webrtc_ffi_description_type(void *desc);

/**
 * Free a description handle.
 */
void webrtc_ffi_description_free(void *desc);

/**
 * Add a transceiver of the given codec kind with the given direction.
 * Returns 0 on success, negative on error.
 */
int webrtc_ffi_add_transceiver(void *peer, int kind, int direction);

/**
 * Add a remote ICE candidate. `candidate` is the SDP candidate attribute string,
 * `sdp_mid` is the media stream identification (may be null), `sdp_mline_index` the
 * index (pass -1 if unknown).
 */
int webrtc_ffi_add_ice_candidate(void *peer,
                                 const char *candidate,
                                 const char *sdp_mid,
                                 int sdp_mline_index);

/**
 * Create a data channel. Returns a handle id (u16) >= 0 on success, negative on error.
 */
int webrtc_ffi_create_data_channel(void *peer, const char *label, bool ordered);

/**
 * Register callbacks for a data channel (by id) on a specific peer.
 */
void webrtc_ffi_data_channel_set_callbacks(void *peer,
                                           uint16_t id,
                                           void (*on_message)(uint16_t, const uint8_t*, uintptr_t),
                                           void (*on_open)(uint16_t),
                                           void (*on_close)(uint16_t));

/**
 * Send UTF-8 text on a data channel. Returns 0 on success.
 */
int webrtc_ffi_data_channel_send_text(void *peer, uint16_t id, const char *text);

/**
 * Send raw bytes on a data channel. Returns 0 on success.
 */
int webrtc_ffi_data_channel_send_bytes(void *peer, uint16_t id, const uint8_t *data, uintptr_t len);

/**
 * Return the full list of supported codecs as a NUL-terminated string.
 *
 * The returned string is owned by the caller and must be freed with
 * [`webrtc_ffi_free_string`]. Each codec is on its own line, with fields
 * separated by horizontal tabs:
 *
 * ```text
 * <kind>\t<mime_type>\t<clock_rate>\t<channels>\t<payload_type>\t<fmtp>
 * ```
 *
 * `<kind>` is `audio` or `video`. `<channels>` is `0` for video codecs.
 */
char *webrtc_ffi_supported_codecs(void);

/**
 * Add a recvonly or sendrecv transceiver of the given kind.
 * `kind`: 0=audio, 1=video. `direction`: 0=recvonly, 3=sendrecv.
 * Returns 0 on success.
 */
int webrtc_ffi_add_transceiver_from_kind(void *peer, int kind, int direction);

/**
 * Get inbound RTP stream stats as a JSON string. Returns a NUL-terminated
 * C string the caller must free with `webrtc_ffi_free_string`.
 */
char *webrtc_ffi_get_stats(void *peer);

/**
 * Get the SSRCs of a remote track as a space-separated string.
 * Returns a NUL-terminated C string the caller must free.
 */
char *webrtc_ffi_track_remote_ssrcs(uint32_t track_id);

/**
 * Get the codec info of a remote track as a tab-separated string:
 * `mime_type\tpayload_type\tclock_rate\tchannels\tsdp_fmtp_line`
 */
char *webrtc_ffi_track_remote_codec(uint32_t track_id, uint32_t ssrc);

/**
 * Get the kind of a remote track: 0=audio, 1=video, -1=error.
 */
int webrtc_ffi_track_remote_kind(uint32_t track_id);

/**
 * Get the RID of a remote track for a given SSRC. Returns NUL-terminated
 * C string (empty if no RID).
 */
char *webrtc_ffi_track_remote_rid(uint32_t track_id, uint32_t ssrc);

/**
 * Send a PictureLossIndication (PLI) RTCP packet to a remote track.
 * `media_ssrc` is the SSRC of the media stream.
 */
int webrtc_ffi_track_remote_write_rtcp(uint32_t track_id, uint32_t media_ssrc);

/**
 * Register event callbacks for a remote track. The poller will be spawned
 * automatically when the track is received via `on_track`.
 */
void webrtc_ffi_track_remote_set_callbacks(uint32_t track_id,
                                           void (*on_rtp)(uint32_t,
                                                          const uint8_t*,
                                                          uintptr_t,
                                                          uint8_t,
                                                          uint16_t,
                                                          uint32_t,
                                                          uint32_t),
                                           void (*on_open)(uint32_t, uint32_t, const char*));

/**
 * Get the track ID of a remote track. Returns a NUL-terminated C string.
 */
char *webrtc_ffi_track_remote_id(uint32_t track_id);

/**
 * Get the label of a remote track. Returns a NUL-terminated C string.
 */
char *webrtc_ffi_track_remote_label(uint32_t track_id);

/**
 * Create a local track for sending media samples. Returns a track handle id
 * (u32) on success, 0 on failure.
 *
 * - `stream_id`, `track_id`, `label`: metadata strings
 * - `kind`: 1=audio, 2=video
 * - `ssrc`: synchronization source identifier
 * - `mime_type`: e.g. "audio/opus", "video/H264", "video/VP8"
 * - `clock_rate`: e.g. 48000 for Opus, 90000 for video
 * - `channels`: 0 for video, 2 for stereo Opus
 * - `sdp_fmtp_line`: codec-specific SDP fmtp, may be empty
 */
uint32_t webrtc_ffi_create_track_local(const char *stream_id,
                                       const char *track_id,
                                       const char *label,
                                       int kind,
                                       uint32_t ssrc,
                                       const char *mime_type,
                                       uint32_t clock_rate,
                                       int channels,
                                       const char *sdp_fmtp_line);

/**
 * Add a local track to a peer connection. Returns a sender handle id (>= 1)
 * on success, negative on error.
 */
int webrtc_ffi_add_track(void *peer, uint32_t track_id);

/**
 * Write a media sample to a local track. The data is the raw codec bitstream
 * (e.g. NAL unit for H.264, IVF frame for VP8, Opus packet).
 *
 * - `track_id`: handle from `webrtc_ffi_create_track_local`
 * - `ssrc`: must match the SSRC used at track creation
 * - `payload_type`: negotiated payload type from `webrtc_ffi_sender_get_payload_type`
 * - `data` / `len`: raw codec frame bytes
 * - `duration_ms`: sample duration in milliseconds
 */
int webrtc_ffi_write_sample(uint32_t track_id,
                            uint32_t ssrc,
                            uint8_t payload_type,
                            const uint8_t *data,
                            uintptr_t len,
                            uint32_t duration_ms);

/**
 * Get sender handle IDs for a peer connection as a space-separated string.
 * Returns a NUL-terminated C string the caller must free.
 */
char *webrtc_ffi_get_senders(void *peer);

/**
 * Remove a local track from a peer connection by sender handle id.
 * Returns 0 on success, negative on error.
 */
int webrtc_ffi_remove_track(void *peer, uint32_t sender_id);

/**
 * Get the negotiated payload type for a sender. Returns the PT (0-255) or
 * -1 on error.
 */
int webrtc_ffi_sender_get_payload_type(uint32_t sender_id);

/**
 * Get the negotiated codec info for a sender as a tab-separated string:
 * `mime_type\tpayload_type\tclock_rate\tchannels\tsdp_fmtp_line`
 * Returns a NUL-terminated C string the caller must free.
 */
char *webrtc_ffi_sender_get_codec(uint32_t sender_id);

#endif /* WEBRTC_FFI_H */
