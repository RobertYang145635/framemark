//! Host harness: three lossless encode paths over a synthetic image, each
//! decoded back with libwebp; prints the bitstream chunk and the max diff.
fn chunk(b: &[u8]) -> String {
    if b.len() < 16 { return "<short>".into(); }
    let mut out = Vec::new();
    let mut i = 12usize;
    while i + 8 <= b.len() {
        out.push(String::from_utf8_lossy(&b[i..i + 4]).to_string());
        let n = u32::from_le_bytes([b[i + 4], b[i + 5], b[i + 6], b[i + 7]]) as usize;
        i += 8 + n + (n & 1);
    }
    out.join("+")
}

fn check(name: &str, enc: &[u8], rgba: &[u8], w: u32, h: u32) {
    unsafe {
        let mut dw = 0i32;
        let mut dh = 0i32;
        let out = libwebp_sys::WebPDecodeRGBA(enc.as_ptr(), enc.len(), &mut dw, &mut dh);
        if out.is_null() {
            println!("{name}: DECODE_FAIL bytes={} chunks={}", enc.len(), chunk(enc));
            return;
        }
        let n = (dw as usize) * (dh as usize) * 4;
        let dec = std::slice::from_raw_parts(out, n);
        let mut max = 0i32;
        let mut diff_px = 0usize;
        if n == (w as usize) * (h as usize) * 4 {
            for i in 0..n / 4 {
                let mut d = 0i32;
                for c in 0..4 { d = d.max((dec[i * 4 + c] as i32 - rgba[i * 4 + c] as i32).abs()); }
                if d > 0 { diff_px += 1; }
                max = max.max(d);
            }
        } else {
            max = -1;
        }
        libwebp_sys::WebPFree(out as *mut std::os::raw::c_void);
        println!("{name}: bytes={} chunks={} decoded={}x{} max_abs={} diff_px={}", enc.len(), chunk(enc), dw, dh, max, diff_px);
    }
}

fn main() {
    use libwebp_sys as webp;
    let (w, h) = (257u32, 193u32);
    let mut rgba = vec![0u8; (w * h * 4) as usize];
    for y in 0..h {
        for x in 0..w {
            let i = ((y * w + x) * 4) as usize;
            rgba[i] = (x % 256) as u8;
            rgba[i + 1] = (y % 256) as u8;
            rgba[i + 2] = ((x * 7 + y * 13) % 256) as u8;
            rgba[i + 3] = 255;
        }
    }
    // A: manual lossless config
    unsafe {
        let mut cfg: webp::WebPConfig = std::mem::zeroed();
        if webp::WebPConfigInitInternal(&mut cfg, webp::WEBP_PRESET_DEFAULT, 100.0, webp::WEBP_ENCODER_ABI_VERSION) != 0 {
            cfg.lossless = 1;
            cfg.quality = 100.0;
            cfg.method = 6;
            let mut pic: webp::WebPPicture = std::mem::zeroed();
            if webp::WebPPictureInitInternal(&mut pic, webp::WEBP_ENCODER_ABI_VERSION) != 0 {
                pic.width = w as i32;
                pic.height = h as i32;
                if webp::WebPPictureImportRGBA(&mut pic, rgba.as_ptr(), (w * 4) as i32) != 0 {
                    let mut writer: webp::WebPMemoryWriter = std::mem::zeroed();
                    webp::WebPMemoryWriterInit(&mut writer);
                    extern "C" fn cb(data: *const u8, size: usize, pic: *const webp::WebPPicture) -> std::os::raw::c_int {
                        unsafe { webp::WebPMemoryWrite(data, size, pic) }
                    }
                    pic.writer = Some(cb);
                    pic.custom_ptr = &mut writer as *mut _ as *mut std::os::raw::c_void;
                    let ok = webp::WebPEncode(&cfg, &mut pic);
                    if ok == 0 { println!("A: ENCODE_FAIL"); } else {
                        let v = std::slice::from_raw_parts(writer.mem, writer.size).to_vec();
                        check("A manual-lossless", &v, &rgba, w, h);
                        println!("A write tmp: {}", v.len());
                        std::fs::write(std::env::temp_dir().join("webptest-a.webp"), &v).ok();
                    }
                    webp::WebPMemoryWriterClear(&mut writer);
                    webp::WebPPictureFree(&mut pic);
                }
            }
        }
    }
    // B: plain lossless API
    unsafe {
        let mut out: *mut u8 = std::ptr::null_mut();
        let n = webp::WebPEncodeLosslessRGBA(rgba.as_ptr(), w as i32, h as i32, (w * 4) as i32, &mut out);
        if n > 0 && !out.is_null() {
            let v = std::slice::from_raw_parts(out, n).to_vec();
            webp::WebPFree(out as *mut std::os::raw::c_void);
            std::fs::write(std::env::temp_dir().join("webptest-b.webp"), &v).ok();
            check("B WebPEncodeLosslessRGBA", &v, &rgba, w, h);
        } else {
            println!("B: ENCODE_FAIL n={n}");
        }
    }
    // C: the crate path (preset)
    match fm_android_export::webp_probe(&rgba, w, h, true, 100.0) {
        Ok(v) => check("C crate preset", &v, &rgba, w, h),
        Err(e) => println!("C: ENCODE_FAIL {e}"),
    }
}
