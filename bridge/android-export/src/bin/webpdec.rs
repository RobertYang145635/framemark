//! Host harness: decode a WebP with libwebp (the same build the app links) and
//! write raw RGBA. `webpdec <in.webp> <out.rgba>`; prints width/height/has_alpha.
fn main() {
    let a: Vec<String> = std::env::args().collect();
    if a.len() < 3 {
        eprintln!("usage: webpdec <in.webp> <out.rgba>");
        std::process::exit(2);
    }
    let data = std::fs::read(&a[1]).expect("read webp");
    let mut w = 0i32;
    let mut h = 0i32;
    unsafe {
        let out = libwebp_sys::WebPDecodeRGBA(data.as_ptr(), data.len(), &mut w, &mut h);
        if out.is_null() {
            eprintln!("WEBP_DECODE_FAIL");
            std::process::exit(1);
        }
        let n = (w as usize) * (h as usize) * 4;
        std::fs::write(&a[2], std::slice::from_raw_parts(out, n)).expect("write rgba");
        libwebp_sys::WebPFree(out as *mut std::os::raw::c_void);
        println!("WEBP_OK w={w} h={h}");
    }
}
