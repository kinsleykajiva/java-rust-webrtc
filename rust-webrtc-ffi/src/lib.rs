use std::ffi::c_char;
use std::ffi::CString;

#[no_mangle]
pub extern "C" fn webrtc_ffi_init() -> *mut c_char {
    let s = CString::new("WebRTC FFI Initialized").unwrap();
    s.into_raw()
}

#[no_mangle]
pub extern "C" fn webrtc_ffi_free_string(s: *mut c_char) {
    if s.is_null() { return; }
    unsafe {
        let _ = CString::from_raw(s);
    }
}
