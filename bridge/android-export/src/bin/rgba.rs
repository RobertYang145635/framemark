//! Host harness: decode a PNG through the export bridge's Skia path and write
//! raw RGBA (`<out>`), so a WebP's pixels can be compared to the encoder input.
//! usage: rgba <in.png> <out.rgba>
fn main() {
    let a: Vec<String> = std::env::args().collect();
    if a.len() < 3 {
        eprintln!("usage: rgba <in.png> <out.rgba>");
        std::process::exit(2);
    }
    let png = std::fs::read(&a[1]).expect("read png");
    match fm_android_export::decode_png(&png) {
        Ok((rgba, w, h)) => {
            std::fs::write(&a[2], &rgba).expect("write rgba");
            println!("RGBA_OK w={w} h={h} bytes={}", rgba.len());
        }
        Err(e) => {
            eprintln!("RGBA_FAIL {e}");
            std::process::exit(1);
        }
    }
}
