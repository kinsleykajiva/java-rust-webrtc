#ifndef WEBRTC_FFI_H
#define WEBRTC_FFI_H

#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>

char *webrtc_ffi_init(void);

void webrtc_ffi_free_string(char *s);

#endif /* WEBRTC_FFI_H */
