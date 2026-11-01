//! Host pre-flight: PNG + ICC + EXIF source -> E5-style JPEG (q100 4:4:4).
//! x1exp <in.png> <icc> <exif-src.jpg> <out.jpg> [--remove-gps 0|1] [--keep-thumb 0|1]
//! Uses the same `encode_export_png` entry the JNI calls.
fn main() {
    let a: Vec<String> = std::env::args().collect();
    if a.len() < 5 {
        eprintln!("usage: x1exp <in.png> <icc> <exif-src.jpg> <out.jpg>");
        std::process::exit(2);
    }
    let flag = |name: &str, dflt: i32| -> i32 {
        a.iter().position(|x| x == name).and_then(|i| a.get(i + 1)).and_then(|v| v.parse().ok()).unwrap_or(dflt)
    };
    let png = std::fs::read(&a[1]).expect("read png");
    let icc = std::fs::read(&a[2]).expect("icc");
    let src = std::fs::read(&a[3]).expect("exif src");
    match fm_android_export::encode_export_png(&png, Some(&icc), Some(&src), flag("--remove-gps", 1) != 0, flag("--keep-thumb", 1) != 0) {
        Ok((bytes, report)) => {
            std::fs::write(&a[4], &bytes).expect("write jpg");
            println!("X1EXP_OK {report}");
        }
        Err(e) => {
            eprintln!("X1EXP_FAIL {e}");
            std::process::exit(1);
        }
    }
}
