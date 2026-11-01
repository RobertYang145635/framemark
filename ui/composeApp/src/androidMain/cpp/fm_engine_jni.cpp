// X1 JNI surface for the native render engines (Android).
//
// Three Rust static libraries are linked on arm64:
//   * framemark/bridge/android       — IR styles (e2b IR solver + text engine)
//   * framemark/bridge/android-hand  — hand templates (e4-hand modules)
//   * framemark/bridge/android-export — q100 4:4:4 JPEG + ICC + EXIF (E5 API)
// All rasterise/encode on the CPU with the T0b Android Skia prebuilt, so no
// second libjpeg enters the process. On ABIs without the libraries only the
// probe functions exist; Kotlin treats a missing symbol as "engine unavailable"
// and keeps the placeholder workflow.

#include <jni.h>

#include <cstddef>

namespace {

constexpr const char *kVersion = "framemark-engine 0.1.0-x1 (jni stub)";

}  // namespace

#ifdef FM_ENGINE_LINKED

// The T0b Android Skia prebuilt was built with a newer libc++ than NDK r26 and
// references std::__ndk1::__hash_memory, which this NDK's libc++ does not
// export (it is only used for unordered-container hashing; any deterministic,
// well-distributed hash is valid). Shim it so the app link can stay
// --no-undefined. FNV-1a 64. Shared by both engine libraries.
namespace std {
namespace __ndk1 {
size_t __hash_memory(const void *ptr, size_t len) noexcept {
  const auto *p = static_cast<const unsigned char *>(ptr);
  size_t h = 1469598103934665603ull;
  for (size_t i = 0; i < len; ++i) {
    h ^= static_cast<size_t>(p[i]);
    h *= 1099511628211ull;
  }
  return h;
}
}  // namespace __ndk1
}  // namespace std

extern "C" {
const char *fm_engine_version();
char *fm_render_design(const char *style_path, const char *photo_path,
                       const char *photo_override, const char *exif_path, const char *assets_json,
                       const char *fonts_dir, const char *brands_dir,
                       const char *fallback_dir, const char *theme,
                       const char *ratio, const char *design_json, const char *overlay_path,
                       float overlay_x, float overlay_y, float overlay_w,
                       float overlay_h, float overlay_opacity,
                       const char *out_png, int max_edge);
char *fm_tiff_to_png(const char *tiff_path, const char *out_png);
void fm_free_string(char *ptr);
}
#endif

#ifdef FM_HAND_LINKED
extern "C" {
char *fm_render_hand(const char *cards_path, const char *card_id, const char *hand_dir,
                     const char *fonts_dir, const char *extra_fonts_dir,
                     const char *fallback_cjk, const char *photo_path,
                     const char *photo_override,
                     const char *overlay_path, float overlay_x, float overlay_y,
                     float overlay_w, float overlay_h, float overlay_opacity,
                     const char *design_json, const char *out_png, int max_edge);
void fm_hand_free_string(char *ptr);
}
#endif

#ifdef FM_EXPORT_LINKED
extern "C" {
char *fm_export_png(const char *png_path, const char *icc_path, const char *exif_src,
                    int remove_gps, int keep_thumb, const char *out_jpeg,
                    const char *report_json);
char *fm_export_webp(const char *png_path, const char *icc_path, const char *exif_src,
                     int remove_gps, int keep_thumb, int lossless, float quality,
                     const char *out_webp, const char *report_json);
void fm_export_free_string(char *ptr);
}
#endif

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
  JNIEnv *env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
    return JNI_ERR;
  }
  return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_framemark_native_engine_NativeEngineProbe_nativePing(JNIEnv *env, jclass) {
#if defined(FM_ENGINE_LINKED) || defined(FM_HAND_LINKED) || defined(FM_EXPORT_LINKED)
  return env->NewStringUTF("fm-android-engine 0.1.0 (ir+hand+export, cpu raster)");
#else
  return env->NewStringUTF(kVersion);
#endif
}

extern "C" JNIEXPORT jint JNICALL
Java_com_framemark_native_engine_NativeEngineProbe_nativeAbiBits(JNIEnv *, jclass) {
#if defined(__aarch64__)
  return 64;
#elif defined(__arm__)
  return 32;
#else
  return 0;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_framemark_native_engine_NativeEngineProbe_nativeRenderDesign(
    JNIEnv *env, jclass, jstring style_path, jstring photo_path, jstring photo_override, jstring exif_path,
    jstring assets_json, jstring fonts_dir, jstring brands_dir, jstring fallback_dir,
    jstring theme, jstring ratio, jstring design_json, jstring overlay_path, jfloat overlay_x, jfloat overlay_y,
    jfloat overlay_w, jfloat overlay_h, jfloat overlay_opacity, jstring out_png, jint max_edge) {
#ifndef FM_ENGINE_LINKED
  (void)style_path; (void)photo_path; (void)photo_override; (void)exif_path; (void)assets_json;
  (void)fonts_dir; (void)brands_dir; (void)fallback_dir; (void)theme; (void)ratio;
  (void)design_json;
  (void)overlay_path; (void)overlay_x; (void)overlay_y; (void)overlay_w; (void)overlay_h;
  (void)overlay_opacity; (void)out_png; (void)max_edge;
  return env->NewStringUTF("IR engine not linked for this ABI");
#else
  const char *style = env->GetStringUTFChars(style_path, nullptr);
  const char *photo = env->GetStringUTFChars(photo_path, nullptr);
  const char *photo_ovr = env->GetStringUTFChars(photo_override, nullptr);
  const char *exif = env->GetStringUTFChars(exif_path, nullptr);
  const char *assets = env->GetStringUTFChars(assets_json, nullptr);
  const char *fonts = env->GetStringUTFChars(fonts_dir, nullptr);
  const char *brands = env->GetStringUTFChars(brands_dir, nullptr);
  const char *fallback = env->GetStringUTFChars(fallback_dir, nullptr);
  const char *theme_c = env->GetStringUTFChars(theme, nullptr);
  const char *ratio_c = env->GetStringUTFChars(ratio, nullptr);
  const char *design_c = env->GetStringUTFChars(design_json, nullptr);
  const char *overlay_c = env->GetStringUTFChars(overlay_path, nullptr);
  const char *out = env->GetStringUTFChars(out_png, nullptr);
  char *err = fm_render_design(style, photo, photo_ovr, exif, assets, fonts, brands, fallback,
                               theme_c, ratio_c, design_c, overlay_c, overlay_x, overlay_y,
                               overlay_w, overlay_h, overlay_opacity, out, max_edge);
  env->ReleaseStringUTFChars(style_path, style);
  env->ReleaseStringUTFChars(photo_path, photo);
  env->ReleaseStringUTFChars(photo_override, photo_ovr);
  env->ReleaseStringUTFChars(exif_path, exif);
  env->ReleaseStringUTFChars(assets_json, assets);
  env->ReleaseStringUTFChars(fonts_dir, fonts);
  env->ReleaseStringUTFChars(brands_dir, brands);
  env->ReleaseStringUTFChars(fallback_dir, fallback);
  env->ReleaseStringUTFChars(theme, theme_c);
  env->ReleaseStringUTFChars(ratio, ratio_c);
  env->ReleaseStringUTFChars(design_json, design_c);
  env->ReleaseStringUTFChars(overlay_path, overlay_c);
  env->ReleaseStringUTFChars(out_png, out);
  if (err == nullptr) return nullptr;
  jstring msg = env->NewStringUTF(err);
  fm_free_string(err);
  return msg;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_framemark_native_engine_NativeEngineProbe_nativeRenderHand(
    JNIEnv *env, jclass, jstring cards_path, jstring card_id, jstring hand_dir,
    jstring fonts_dir, jstring extra_fonts_dir, jstring fallback_cjk, jstring photo_path,
    jstring photo_override,
    jstring overlay_path, jfloat overlay_x, jfloat overlay_y, jfloat overlay_w,
    jfloat overlay_h, jfloat overlay_opacity, jstring design_json, jstring out_png, jint max_edge) {
#ifndef FM_HAND_LINKED
  (void)cards_path; (void)card_id; (void)hand_dir; (void)fonts_dir;
  (void)extra_fonts_dir; (void)fallback_cjk; (void)photo_path; (void)photo_override; (void)overlay_path;
  (void)overlay_x; (void)overlay_y; (void)overlay_w; (void)overlay_h; (void)overlay_opacity;
  (void)design_json; (void)out_png; (void)max_edge;
  return env->NewStringUTF("hand engine not linked for this ABI");
#else
  const char *cards = env->GetStringUTFChars(cards_path, nullptr);
  const char *card = env->GetStringUTFChars(card_id, nullptr);
  const char *hand = env->GetStringUTFChars(hand_dir, nullptr);
  const char *fonts = env->GetStringUTFChars(fonts_dir, nullptr);
  const char *extra = env->GetStringUTFChars(extra_fonts_dir, nullptr);
  const char *fallback = env->GetStringUTFChars(fallback_cjk, nullptr);
  const char *photo = env->GetStringUTFChars(photo_path, nullptr);
  const char *photo_ovr = env->GetStringUTFChars(photo_override, nullptr);
  const char *overlay_c = env->GetStringUTFChars(overlay_path, nullptr);
  const char *design_c = env->GetStringUTFChars(design_json, nullptr);
  const char *out = env->GetStringUTFChars(out_png, nullptr);
  char *err = fm_render_hand(cards, card, hand, fonts, extra, fallback, photo, photo_ovr,
                             overlay_c, overlay_x, overlay_y, overlay_w, overlay_h,
                             overlay_opacity, design_c, out, max_edge);
  env->ReleaseStringUTFChars(cards_path, cards);
  env->ReleaseStringUTFChars(card_id, card);
  env->ReleaseStringUTFChars(hand_dir, hand);
  env->ReleaseStringUTFChars(fonts_dir, fonts);
  env->ReleaseStringUTFChars(extra_fonts_dir, extra);
  env->ReleaseStringUTFChars(fallback_cjk, fallback);
  env->ReleaseStringUTFChars(photo_path, photo);
  env->ReleaseStringUTFChars(photo_override, photo_ovr);
  env->ReleaseStringUTFChars(overlay_path, overlay_c);
  env->ReleaseStringUTFChars(design_json, design_c);
  env->ReleaseStringUTFChars(out_png, out);
  if (err == nullptr) return nullptr;
  jstring msg = env->NewStringUTF(err);
  fm_hand_free_string(err);
  return msg;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_framemark_native_engine_NativeEngineProbe_nativeTiffToPng(
    JNIEnv *env, jclass, jstring tiff_path, jstring out_png) {
#ifndef FM_ENGINE_LINKED
  (void)tiff_path; (void)out_png;
  return env->NewStringUTF("IR engine not linked for this ABI");
#else
  const char *tiff = env->GetStringUTFChars(tiff_path, nullptr);
  const char *out = env->GetStringUTFChars(out_png, nullptr);
  char *err = fm_tiff_to_png(tiff, out);
  env->ReleaseStringUTFChars(tiff_path, tiff);
  env->ReleaseStringUTFChars(out_png, out);
  if (err == nullptr) return nullptr;
  jstring msg = env->NewStringUTF(err);
  fm_free_string(err);
  return msg;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_framemark_native_engine_NativeEngineProbe_nativeExportWebp(
    JNIEnv *env, jclass, jstring png_path, jstring icc_path, jstring exif_src,
    jint remove_gps, jint keep_thumb, jint lossless, jfloat quality,
    jstring out_webp, jstring report_json) {
#ifndef FM_EXPORT_LINKED
  (void)png_path; (void)icc_path; (void)exif_src; (void)remove_gps;
  (void)keep_thumb; (void)lossless; (void)quality; (void)out_webp; (void)report_json;
  return env->NewStringUTF("{\"ok\":false,\"error\":\"export encoder not linked for this ABI\"}");
#else
  const char *png = env->GetStringUTFChars(png_path, nullptr);
  const char *icc = env->GetStringUTFChars(icc_path, nullptr);
  const char *exif = env->GetStringUTFChars(exif_src, nullptr);
  const char *out = env->GetStringUTFChars(out_webp, nullptr);
  const char *report = env->GetStringUTFChars(report_json, nullptr);
  char *json = fm_export_webp(png, icc, exif, remove_gps, keep_thumb, lossless, quality, out, report);
  env->ReleaseStringUTFChars(png_path, png);
  env->ReleaseStringUTFChars(icc_path, icc);
  env->ReleaseStringUTFChars(exif_src, exif);
  env->ReleaseStringUTFChars(out_webp, out);
  env->ReleaseStringUTFChars(report_json, report);
  if (json == nullptr) {
    return env->NewStringUTF("{\"ok\":false,\"error\":\"webp export returned no envelope\"}");
  }
  jstring result = env->NewStringUTF(json);
  fm_export_free_string(json);
  return result;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_framemark_native_engine_NativeEngineProbe_nativeExportPng(
    JNIEnv *env, jclass, jstring png_path, jstring icc_path, jstring exif_src,
    jint remove_gps, jint keep_thumb, jstring out_jpeg, jstring report_json) {
#ifndef FM_EXPORT_LINKED
  (void)png_path; (void)icc_path; (void)exif_src; (void)remove_gps;
  (void)keep_thumb; (void)out_jpeg; (void)report_json;
  return env->NewStringUTF("{\"ok\":false,\"error\":\"export encoder not linked for this ABI\"}");
#else
  const char *png = env->GetStringUTFChars(png_path, nullptr);
  const char *icc = env->GetStringUTFChars(icc_path, nullptr);
  const char *exif = env->GetStringUTFChars(exif_src, nullptr);
  const char *out = env->GetStringUTFChars(out_jpeg, nullptr);
  const char *report = env->GetStringUTFChars(report_json, nullptr);
  char *json = fm_export_png(png, icc, exif, remove_gps, keep_thumb, out, report);
  env->ReleaseStringUTFChars(png_path, png);
  env->ReleaseStringUTFChars(icc_path, icc);
  env->ReleaseStringUTFChars(exif_src, exif);
  env->ReleaseStringUTFChars(out_jpeg, out);
  env->ReleaseStringUTFChars(report_json, report);
  if (json == nullptr) {
    return env->NewStringUTF("{\"ok\":false,\"error\":\"export returned no envelope\"}");
  }
  jstring result = env->NewStringUTF(json);
  fm_export_free_string(json);
  return result;
#endif
}
