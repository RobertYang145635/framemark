//! Bounded interactive source decode, shared by every renderer entry point that
//! paints a user photo.
//!
//! The interactive preview tier may decode the source photo at a reduced raster
//! instead of full resolution. Layout, crop windows and the geometry report keep
//! solving in the photo file's own pixels (`logical_w`/`logical_h`); only the
//! pixels Skia samples come from the smaller decode. Two consequences make this
//! safe:
//!
//! * the raster/photo ratio is carried next to the image, so a draw multiplies
//!   its source rect into the raster and nothing else moves, and
//! * the reduced raster is accepted only when Skia's full decode and its scaled
//!   decode agree on the EXIF-orientation regime (see [`decode_bounded`]), so the
//!   scaled frame can never come out rotated or transposed against the full one.
//!
//! The decode is cached under [`PhotoKey`] = (path, length, mtime, source edge):
//! a photo replaced on disk is decoded again, and the full-quality frame after a
//! drag is a different edge, hence a different entry — it neither reuses nor
//! evicts the interactive decode. [`PhotoCache`] is a tiny MRU whose decisions are
//! pure and tested; each renderer keeps its own instance (the Android IR bridge,
//! the Android hand bridge and the desktop hand engine are separate crates, so
//! they each compile this module and own their slot).

use std::path::{Path, PathBuf};
use std::time::{Instant, SystemTime};

use skia_safe::codec::Codec;
use skia_safe::{Data, Image, ImageInfo, Rect};

/// A decoded source photo plus the ratio between its raster and the photo's
/// logical (full-resolution) pixels. `scale < 1.0` means the image was decoded
/// down for an interactive frame: geometry is still solved from
/// `logical_w`/`logical_h`, so only the sampled resolution changes.
#[derive(Clone)]
pub struct PhotoSource {
    pub image: Image,
    pub scale: f32,
    pub logical_w: i32,
    pub logical_h: i32,
}

impl PhotoSource {
    /// The untouched full-resolution decode (`scale == 1`).
    pub fn full(image: Image) -> Self {
        let (w, h) = (image.width(), image.height());
        Self {
            image,
            scale: 1.0,
            logical_w: w,
            logical_h: h,
        }
    }

    /// True when this raster is smaller than the photo's logical pixels.
    pub fn is_scaled(&self) -> bool {
        self.scale < 1.0
    }

    /// The photo's logical size as layout code wants it.
    pub fn logical_size(&self) -> (f32, f32) {
        (self.logical_w as f32, self.logical_h as f32)
    }

    /// Map a source rect written in the photo's logical pixels onto this raster.
    /// `scale == 1.0` returns the rect bit-for-bit, so the full decode keeps
    /// sampling exactly the rectangle it always did.
    pub fn src_rect(&self, logical: Rect) -> Rect {
        if self.scale == 1.0 {
            return logical;
        }
        Rect::from_xywh(
            logical.left() * self.scale,
            logical.top() * self.scale,
            logical.width() * self.scale,
            logical.height() * self.scale,
        )
    }
}

/// Identity of one decode: a photo replaced on disk (different length/mtime), a
/// different photo, or a different interactive source edge is a cache miss.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PhotoKey {
    pub path: PathBuf,
    pub len: u64,
    pub modified: Option<SystemTime>,
    pub source_edge: u32,
}

impl PhotoKey {
    /// The key a decode of `path` at `source_edge` would be cached under.
    pub fn of(path: &Path, source_edge: u32) -> Result<Self, String> {
        let meta = std::fs::metadata(path)
            .map_err(|e| format!("photo missing: {}: {e}", path.display()))?;
        Ok(Self {
            path: path.to_path_buf(),
            len: meta.len(),
            modified: meta.modified().ok(),
            source_edge,
        })
    }
}

/// What a bounded decode actually did, for the caller's evidence line.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DecodeOutcome {
    /// `edge` did not reduce the photo: the plain full decode is in use.
    Full,
    /// The codec produced a smaller raster for the same geometry.
    Scaled,
    /// A reduced decode was asked for, but the codec could not shrink this file
    /// (unsupported downsample, or its orientation regime disagreed with the
    /// full decode). The full raster is in use, so the frame is still correct.
    Unbounded,
    /// Served from [`PhotoCache`] without touching the file's pixels.
    Cached,
}

impl DecodeOutcome {
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::Full => "full",
            Self::Scaled => "scaled",
            Self::Unbounded => "unbounded",
            Self::Cached => "cached",
        }
    }
}

/// Evidence for one decode: what was asked for, what was produced and how long
/// it took. Callers print it under their own tag so the device log keeps one
/// line per rendered frame.
#[derive(Clone, Debug)]
pub struct DecodeReport {
    pub edge: u32,
    pub outcome: DecodeOutcome,
    pub decode_us: u128,
    pub logical_w: i32,
    pub logical_h: i32,
    pub raster_w: i32,
    pub raster_h: i32,
    pub scale: f32,
}

impl DecodeReport {
    fn of(edge: u32, outcome: DecodeOutcome, decode_us: u128, photo: &PhotoSource) -> Self {
        Self {
            edge,
            outcome,
            decode_us,
            logical_w: photo.logical_w,
            logical_h: photo.logical_h,
            raster_w: photo.image.width(),
            raster_h: photo.image.height(),
            scale: photo.scale,
        }
    }

    /// `source=WxH decoded=WxH scale=… edge=… cached=… outcome=… ms=…`
    /// (one shared wording for every engine's evidence line).
    pub fn fields(&self) -> String {
        format!(
            "source={}x{} decoded={}x{} scale={:.6} edge={} cached={} outcome={} ms={}",
            self.logical_w,
            self.logical_h,
            self.raster_w,
            self.raster_h,
            self.scale,
            self.edge,
            self.outcome == DecodeOutcome::Cached,
            self.outcome.as_str(),
            self.decode_us / 1000
        )
    }
}

/// Pure decision: would `edge` actually reduce a photo whose full decode is
/// `full_max_side` pixels on its long edge? `0` means "no bound requested" (the
/// final frame and every export), which never scales.
pub fn wants_scaled_decode(full_max_side: i32, edge: u32) -> bool {
    edge > 0 && full_max_side > 0 && full_max_side as u32 > edge
}

/// Pure decision: is the codec's reduced raster a faithful *smaller* version of
/// the photo? `logical` is the full decode's size (post-orientation), `decoded`
/// the codec's own pre-orientation dimensions and `scaled` what it offered.
pub fn accepts_scaled_dims(decoded: (i32, i32), scaled: (i32, i32), logical: (i32, i32)) -> bool {
    let non_empty = scaled.0 > 0 && scaled.1 > 0;
    let narrower = scaled.0 < decoded.0 && scaled.1 < decoded.1;
    let smaller_than_full = scaled.0.max(scaled.1) < logical.0.max(logical.1);
    non_empty && narrower && smaller_than_full
}

/// Decode `bytes` at no more than `edge` pixels on the long edge, relative to the
/// already known full decode `logical`.
///
/// Skia's codec downsamples JPEG/WebP at a native ratio (1/2, 1/4, 1/8) and
/// applies the encoded EXIF origin, so the reduced frame has the full decode's
/// geometry with fewer sampled pixels. Accepting it takes three guards: the codec
/// must offer a smaller raster, the full decode must agree that an origin was
/// applied (`full_swapped == scaled_swapped`, otherwise the two rasters describe
/// different geometries) and the resulting ratio must be a genuine shrink.
/// `None` means "keep the full raster".
pub fn decode_bounded(bytes: &[u8], edge: u32, logical: (i32, i32)) -> Option<PhotoSource> {
    let (lw, lh) = logical;
    if !wants_scaled_decode(lw.max(lh), edge) {
        return None;
    }
    let mut codec = Codec::from_data(Data::new_copy(bytes))?;
    let full_info = codec.info();
    let decoded_dims = codec.dimensions();
    // `decoded_dims` are pre-orientation; `lw/lh` are what Skia's full decode
    // produced. Both sides must agree on the orientation regime before their
    // width ratio can describe the same raster.
    let full_swapped = lw != decoded_dims.width;
    let desired = edge as f32 / decoded_dims.width.max(decoded_dims.height).max(1) as f32;
    let scaled = codec.get_scaled_dimensions(desired);
    if !accepts_scaled_dims(
        (decoded_dims.width, decoded_dims.height),
        (scaled.width, scaled.height),
        (lw, lh),
    ) {
        return None;
    }
    let info = ImageInfo::new(
        (scaled.width, scaled.height),
        full_info.color_type(),
        full_info.alpha_type(),
        full_info.color_space(),
    );
    let image = codec.get_image(Some(info), None).ok()?;
    // `get_image` applies the EXIF origin. Accept the reduced raster only when
    // the full decode agrees on whether the origin was applied; otherwise the
    // scale below would mix an oriented width with an unoriented one.
    let scaled_swapped = image.width() != scaled.width;
    let scale = image.width() as f32 / lw.max(1) as f32;
    if full_swapped != scaled_swapped || !(scale > 0.0 && scale < 1.0) {
        return None;
    }
    Some(PhotoSource {
        image,
        scale,
        logical_w: lw,
        logical_h: lh,
    })
}

/// Read and decode one photo, bounded by `edge` (0 = full resolution).
///
/// `full_image` is the caller's own full-resolution loader: it is used only for
/// formats Skia's `Image` cannot open (the hand engines decode TIFF themselves)
/// and for files whose pixels decode lazily anyway.
pub fn load_photo(
    path: &Path,
    edge: u32,
    full_image: &dyn Fn(&[u8]) -> Option<Image>,
) -> Result<(PhotoSource, DecodeReport), String> {
    let started = Instant::now();
    let bytes =
        std::fs::read(path).map_err(|e| format!("photo missing: {}: {e}", path.display()))?;
    // `from_encoded` parses the header and leaves the pixels lazy, so this costs
    // no decode and the untouched full image stays exactly today's image.
    let (full, from_codec) = match Image::from_encoded(Data::new_copy(&bytes)) {
        Some(image) => (image, true),
        None => (
            full_image(&bytes).ok_or_else(|| format!("decode failed {}", path.display()))?,
            false,
        ),
    };
    let mut photo = PhotoSource::full(full);
    let logical = (photo.logical_w, photo.logical_h);
    let mut outcome = DecodeOutcome::Full;
    if from_codec && wants_scaled_decode(logical.0.max(logical.1), edge) {
        match decode_bounded(&bytes, edge, logical) {
            Some(bounded) => {
                photo = bounded;
                outcome = DecodeOutcome::Scaled;
            }
            None => outcome = DecodeOutcome::Unbounded,
        }
    }
    let report = DecodeReport::of(edge, outcome, started.elapsed().as_micros(), &photo);
    Ok((photo, report))
}

/// Small MRU of decoded photos. `cap == 1` keeps only the active frame's decode;
/// `cap == 2` also keeps the other tier of the same photo, which is what the
/// desktop worker alternates between (interactive drag and final frame).
pub struct PhotoCache {
    cap: usize,
    entries: Vec<(PhotoKey, PhotoSource)>,
}

impl PhotoCache {
    pub fn new(cap: usize) -> Self {
        Self {
            cap: cap.max(1),
            entries: Vec::new(),
        }
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    /// Look `key` up and make it the most recently used entry on a hit.
    pub fn get(&mut self, key: &PhotoKey) -> Option<PhotoSource> {
        let pos = self.entries.iter().position(|(k, _)| k == key)?;
        let entry = self.entries.remove(pos);
        let photo = entry.1.clone();
        self.entries.insert(0, entry);
        Some(photo)
    }

    /// Store `photo` as the most recently used entry, evicting the oldest.
    pub fn put(&mut self, key: PhotoKey, photo: PhotoSource) {
        self.entries.retain(|(k, _)| k != &key);
        self.entries.insert(0, (key, photo));
        self.entries.truncate(self.cap);
    }
}

/// Decode through `cache`: a hit never reads the file's pixels, a miss decodes
/// once and stores the result. Two entries survive a drag-and-release cycle.
pub fn load_cached_photo(
    cache: &mut PhotoCache,
    path: &Path,
    edge: u32,
    full_image: &dyn Fn(&[u8]) -> Option<Image>,
) -> Result<(PhotoSource, DecodeReport), String> {
    let started = Instant::now();
    let key = PhotoKey::of(path, edge)?;
    if let Some(photo) = cache.get(&key) {
        let report = DecodeReport::of(edge, DecodeOutcome::Cached, started.elapsed().as_micros(), &photo);
        return Ok((photo, report));
    }
    let (photo, _) = load_photo(path, edge, full_image)?;
    cache.put(key, photo.clone());
    let report = DecodeReport::of(edge, photo_outcome(&photo, edge), started.elapsed().as_micros(), &photo);
    Ok((photo, report))
}

/// The outcome a fresh decode of `photo` represents, derived from its raster
/// (the same rule `load_photo` applied, without carrying the value).
fn photo_outcome(photo: &PhotoSource, edge: u32) -> DecodeOutcome {
    if photo.is_scaled() {
        DecodeOutcome::Scaled
    } else if wants_scaled_decode(photo.logical_w.max(photo.logical_h), edge) {
        DecodeOutcome::Unbounded
    } else {
        DecodeOutcome::Full
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    fn write_temp(name: &str, bytes: &[u8]) -> PathBuf {
        let dir = std::env::temp_dir().join("fm-photo-source-tests");
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join(name);
        std::fs::write(&path, bytes).unwrap();
        path
    }

    fn touch(path: &Path, secs: u64) {
        let file = std::fs::OpenOptions::new().write(true).open(path).unwrap();
        file.set_modified(std::time::UNIX_EPOCH + Duration::from_secs(secs))
            .unwrap();
    }

    /// A JPEG this test can bound: a flat raster encoded by Skia itself.
    fn jpeg(w: i32, h: i32) -> Vec<u8> {
        let mut surface = skia_safe::surfaces::raster_n32_premul((w, h)).unwrap();
        surface.canvas().clear(skia_safe::Color::from_rgb(200, 40, 40));
        surface
            .image_snapshot()
            .encode_to_data(skia_safe::EncodedImageFormat::JPEG)
            .unwrap()
            .as_bytes()
            .to_vec()
    }

    #[test]
    fn photo_key_covers_path_length_mtime_and_edge() {
        let a = write_temp("key-a.jpg", b"aaaa");
        let b = write_temp("key-b.jpg", b"aaaa");
        touch(&a, 1_000);
        touch(&b, 1_000);

        let base = PhotoKey::of(&a, 960).unwrap();
        assert_eq!(base, PhotoKey::of(&a, 960).unwrap());
        // A different file is a different key even with identical bytes.
        assert_ne!(base, PhotoKey::of(&b, 960).unwrap());
        // A different interactive edge is a different decode.
        assert_ne!(base, PhotoKey::of(&a, 0).unwrap());
        assert_ne!(base, PhotoKey::of(&a, 1280).unwrap());
        // A replaced photo (new length) is a cache miss.
        std::fs::write(&a, b"aaaaa").unwrap();
        touch(&a, 1_000);
        assert_ne!(base, PhotoKey::of(&a, 960).unwrap());
        // Same length, newer mtime: still a miss.
        std::fs::write(&a, b"aaaa").unwrap();
        touch(&a, 2_000);
        let moved = PhotoKey::of(&a, 960).unwrap();
        assert_ne!(base, moved);
        assert_eq!(moved.modified, Some(std::time::UNIX_EPOCH + Duration::from_secs(2_000)));
    }

    #[test]
    fn cache_keeps_both_tiers_and_evicts_the_oldest() {
        let a = write_temp("cache-a.jpg", b"a");
        let mut cache = PhotoCache::new(2);
        assert!(cache.is_empty());
        let full = PhotoSource::full(skia_safe::surfaces::raster_n32_premul((4, 3)).unwrap().image_snapshot());
        let mut scaled = full.clone();
        scaled.scale = 0.5;

        let key_full = PhotoKey::of(&a, 0).unwrap();
        let key_int = PhotoKey::of(&a, 960).unwrap();
        cache.put(key_full.clone(), full.clone());
        cache.put(key_int.clone(), scaled.clone());
        assert_eq!(cache.len(), 2);
        // Both tiers stay resident: a drag/release cycle never re-decodes.
        assert!(cache.get(&key_full).is_some());
        assert!(cache.get(&key_int).is_some());
        // A third key evicts the least recently used one (full, re-read above
        // second, so `int` is now the oldest).
        let other = PhotoKey::of(&a, 480).unwrap();
        cache.put(other.clone(), full.clone());
        assert_eq!(cache.len(), 2);
        assert!(cache.get(&key_full).is_none());
        assert!(cache.get(&key_int).is_some());
        assert!(cache.get(&other).is_some());
    }

    #[test]
    fn cache_hit_reports_cached_without_decoding() {
        let bytes = jpeg(256, 192);
        let path = write_temp("hit.jpg", &bytes);
        let mut cache = PhotoCache::new(1);
        let (first, report) = load_cached_photo(&mut cache, &path, 64, &|_| None).unwrap();
        assert_eq!(report.outcome, DecodeOutcome::Scaled);
        assert!(first.is_scaled());
        let (again, hit) = load_cached_photo(&mut cache, &path, 64, &|_| None).unwrap();
        assert_eq!(hit.outcome, DecodeOutcome::Cached);
        assert!(hit.decode_us <= 5_000);
        assert_eq!(hit.raster_w, report.raster_w);
        assert_eq!(hit.raster_h, report.raster_h);
        assert_eq!(again.scale, first.scale);
        // A different edge is a different entry: the file is decoded again.
        let (_full, other) = load_cached_photo(&mut cache, &path, 0, &|_| None).unwrap();
        assert_eq!(other.outcome, DecodeOutcome::Full);
        assert_eq!(other.raster_w, 256);
        assert_eq!(cache.len(), 1);
    }

    #[test]
    fn wants_scaled_decode_never_upscales() {
        assert!(!wants_scaled_decode(6144, 0));
        assert!(!wants_scaled_decode(6144, 6144));
        assert!(!wants_scaled_decode(6144, 8192));
        assert!(wants_scaled_decode(6144, 960));
        assert!(!wants_scaled_decode(0, 960));
        assert!(!wants_scaled_decode(1, 1));
    }

    #[test]
    fn accepts_scaled_dims_rejects_no_ops_and_upscales() {
        // 8192x6144 full, 1024x768 offered.
        assert!(accepts_scaled_dims((8192, 6144), (1024, 768), (8192, 6144)));
        // Same size: not a reduction.
        assert!(!accepts_scaled_dims((8192, 6144), (8192, 6144), (8192, 6144)));
        // Empty codec answer.
        assert!(!accepts_scaled_dims((8192, 6144), (0, 0), (8192, 6144)));
        // A reduced raster that is not smaller in *both* dimensions (a rotated
        // panorama whose offered height still spans the full photo).
        assert!(!accepts_scaled_dims((8192, 6144), (2048, 6144), (6144, 8192)));
    }

    #[test]
    fn bounded_decode_shrinks_and_keeps_the_logical_size() {
        let bytes = jpeg(256, 192);
        let full = Image::from_encoded(Data::new_copy(&bytes)).unwrap();
        let logical = (full.width(), full.height());
        let scaled = decode_bounded(&bytes, 64, logical).expect("a 256px JPEG can be bounded to 64");
        assert!(scaled.is_scaled());
        assert_eq!((scaled.logical_w, scaled.logical_h), logical);
        assert!(scaled.image.width() <= 64);
        assert!(scaled.image.width() < full.width());
        assert_eq!((scaled.logical_size()), (256.0, 192.0));
        // The full decode keeps the rect bit-for-bit; the scaled one maps it.
        let rect = Rect::from_xywh(10.0, 20.0, 100.0, 50.0);
        let full_source = PhotoSource::full(full);
        assert_eq!(full_source.src_rect(rect), rect);
        let mapped = scaled.src_rect(rect);
        assert_eq!(mapped.left(), 10.0 * scaled.scale);
        assert_eq!(mapped.top(), 20.0 * scaled.scale);
        assert_eq!(mapped.width(), 100.0 * scaled.scale);
        assert_eq!(mapped.height(), 50.0 * scaled.scale);
    }

    #[test]
    fn bounded_decode_is_off_when_the_edge_covers_the_photo() {
        let bytes = jpeg(64, 48);
        let full = Image::from_encoded(Data::new_copy(&bytes)).unwrap();
        let logical = (full.width(), full.height());
        assert!(decode_bounded(&bytes, 64, logical).is_none());
        assert!(decode_bounded(&bytes, 0, logical).is_none());
        assert!(decode_bounded(&bytes, 4096, logical).is_none());
    }

    #[test]
    fn unbounded_format_falls_back_to_the_callers_full_decode() {
        // Skia cannot open this: the caller's own decoder is the only way in.
        let path = write_temp("not-an-image.bin", b"II*\0not-a-tiff");
        let (photo, report) =
            load_photo(&path, 960, &|_| Some(skia_safe::surfaces::raster_n32_premul((7, 5)).unwrap().image_snapshot()))
                .unwrap();
        assert_eq!(report.outcome, DecodeOutcome::Full);
        assert_eq!((photo.logical_w, photo.logical_h), (7, 5));
        assert!(!photo.is_scaled());
        // ...and a file no decoder can open is still a hard error.
        assert!(load_photo(&path, 960, &|_| None).is_err());
    }

    #[test]
    fn decode_report_fields_are_one_stable_line() {
        let bytes = jpeg(256, 192);
        let path = write_temp("line.jpg", &bytes);
        let (_, report) = load_photo(&path, 64, &|_| None).unwrap();
        assert!(report.fields().starts_with("source=256x192 decoded="));
        assert!(report.fields().contains(" cached=false outcome=scaled ms="));
    }
}
