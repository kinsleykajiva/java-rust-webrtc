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
                             DataChannelCallback on_data_channel);

/**
 * Close and free a peer connection handle.
 */
int webrtc_ffi_peer_close(void *peer);

/**
 * Create an SDP offer. Returns a newly allocated description handle
 * (free with [`webrtc_ffi_description_free`]). On error returns null.
 */
void *webrtc_ffi_create_offer(void *peer);

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
 * Register callbacks for a data channel (by id).
 */
void webrtc_ffi_data_channel_set_callbacks(uint16_t id,
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

#endif /* WEBRTC_FFI_H */
