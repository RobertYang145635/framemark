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

#include <chrono>
#include <cstddef>
#include <string>

namespace {

constexpr const char *kVersion = "framemark-engine 0.1.0-x1 (jni stub)";

class JStringUtf {
 public:
  JStringUtf(JNIEnv *env, jstring value) : env_(env), value_(value), chars_(env->GetStringUTFChars(value, nullptr)) {}
  ~JStringUtf() {
    if (chars_ != nullptr) env_->ReleaseStringUTFChars(value_, chars_);
  }
  JStringUtf(const JStringUtf &) = delete;
  JStringUtf &operator=(const JStringUtf &) = delete;
  const char *get() const { return chars_ == nullptr ? "" : chars_; }

 private:
  JNIEnv *env_;
  jstring value_;
  const char *chars_;
};

jstring ErrorEnvelope(JNIEnv *env, const char *error) {
  std::string escaped;
  for (const char *p = error == nullptr ? "" : error; *p != '\0'; ++p) {
    switch (*p) {
      case '\\': escaped += "\\\\"; break;
      case '"': escaped += "\\\""; break;
      case '\n': escaped += "\\n"; break;
      case '\r': escaped += "\\r"; break;
      case '\t': escaped += "\\t"; break;
      default: escaped += *p; break;
    }
  }
  const std::string json = "{\"ok\":false,\"error\":\"" + escaped + "\"}";
  return env->NewStringUTF(json.c_str());
}

unsigned long long ElapsedMs(std::chrono::steady_clock::time_point started) {
  return static_cast<unsigned long long>(
      std::chrono::duration_cast<std::chrono::milliseconds>(
          std::chrono::steady_clock::now() - started).count());
}

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
                       float overlay_h, float overlay_opacity, float overlay_angle,
                       const char *out_png, int max_edge);
struct FmIrRgbaHandle;
struct FmIrRgbaHandle *fm_render_design_rgba(
    const char *style_path, const char *photo_path, const char *photo_override,
    const char *exif_path, const char *assets_json, const char *fonts_dir,
    const char *brands_dir, const char *fallback_dir, const char *theme,
    const char *ratio, const char *design_json, const char *overlay_path,
    float overlay_x, float overlay_y, float overlay_w, float overlay_h,
    float overlay_opacity, float overlay_angle, const char *geom_path,
    int max_edge, char **out_error);
const unsigned char *fm_rgba_ir_data(const FmIrRgbaHandle *handle);
size_t fm_rgba_ir_len(const FmIrRgbaHandle *handle);
unsigned int fm_rgba_ir_width(const FmIrRgbaHandle *handle);
unsigned int fm_rgba_ir_height(const FmIrRgbaHandle *handle);
void fm_rgba_ir_free(FmIrRgbaHandle *handle);
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
                     float overlay_w, float overlay_h, float overlay_opacity, float overlay_angle,
                     const char *design_json, const char *out_png, int max_edge);
struct FmHandRgbaHandle;
struct FmHandRgbaHandle *fm_render_hand_rgba(
    const char *cards_path, const char *card_id, const char *hand_dir,
    const char *fonts_dir, const char *extra_fonts_dir, const char *fallback_cjk,
    const char *photo_path, const char *photo_override, const char *overlay_path,
    float overlay_x, float overlay_y, float overlay_w, float overlay_h,
    float overlay_opacity, float overlay_angle, const char *design_json,
    const char *geom_path, int max_edge, char **out_error);
const unsigned char *fm_rgba_hand_data(const FmHandRgbaHandle *handle);
size_t fm_rgba_hand_len(const FmHandRgbaHandle *handle);
unsigned int fm_rgba_hand_width(const FmHandRgbaHandle *handle);
unsigned int fm_rgba_hand_height(const FmHandRgbaHandle *handle);
void fm_rgba_hand_free(FmHandRgbaHandle *handle);
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
char *fm_export_jpeg_rgba(const unsigned char *rgba, size_t len, unsigned int w,
                          unsigned int h, const char *icc_path, const char *exif_src,
                          int remove_gps, int keep_thumb, const char *out_jpeg,
                          const char *report_json, unsigned long long render_ms);
char *fm_export_webp_rgba(const unsigned char *rgba, size_t len, unsigned int w,
                          unsigned int h, const char *icc_path, const char *exif_src,
                          int remove_gps, int keep_thumb, int lossless, float quality,
                          const char *out_webp, const char *report_json,
                          unsigned long long render_ms);
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
  return env->NewStringUTF("fm-android-engine 0.2.0 (ir+hand+export, cpu raster)");
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
    jfloat overlay_w, jfloat overlay_h, jfloat overlay_opacity, jfloat overlay_angle,
    jstring out_png, jint max_edge) {
#ifndef FM_ENGINE_LINKED
  (void)style_path; (void)photo_path; (void)photo_override; (void)exif_path; (void)assets_json;
  (void)fonts_dir; (void)brands_dir; (void)fallback_dir; (void)theme; (void)ratio;
  (void)design_json;
  (void)overlay_path; (void)overlay_x; (void)overlay_y; (void)overlay_w; (void)overlay_h;
  (void)overlay_opacity; (void)overlay_angle; (void)out_png; (void)max_edge;
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
  JStringUtf design(env, design_json);
  const char *overlay_c = env->GetStringUTFChars(overlay_path, nullptr);
  const char *out = env->GetStringUTFChars(out_png, nullptr);
  char *err = fm_render_design(style, photo, photo_ovr, exif, assets, fonts, brands, fallback,
                               theme_c, ratio_c, design.get(), overlay_c, overlay_x, overlay_y,
                               overlay_w, overlay_h, overlay_opacity, overlay_angle, out, max_edge);
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
    jfloat overlay_h, jfloat overlay_opacity, jfloat overlay_angle, jstring design_json,
    jstring out_png, jint max_edge) {
#ifndef FM_HAND_LINKED
  (void)cards_path; (void)card_id; (void)hand_dir; (void)fonts_dir;
  (void)extra_fonts_dir; (void)fallback_cjk; (void)photo_path; (void)photo_override; (void)overlay_path;
  (void)overlay_x; (void)overlay_y; (void)overlay_w; (void)overlay_h; (void)overlay_opacity;
  (void)overlay_angle; (void)design_json; (void)out_png; (void)max_edge;
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
  JStringUtf design(env, design_json);
  const char *out = env->GetStringUTFChars(out_png, nullptr);
  char *err = fm_render_hand(cards, card, hand, fonts, extra, fallback, photo, photo_ovr,
                             overlay_c, overlay_x, overlay_y, overlay_w, overlay_h,
                             overlay_opacity, overlay_angle, design.get(), out, max_edge);
  env->ReleaseStringUTFChars(cards_path, cards);
  env->ReleaseStringUTFChars(card_id, card);
  env->ReleaseStringUTFChars(hand_dir, hand);
  env->ReleaseStringUTFChars(fonts_dir, fonts);
  env->ReleaseStringUTFChars(extra_fonts_dir, extra);
  env->ReleaseStringUTFChars(fallback_cjk, fallback);
  env->ReleaseStringUTFChars(photo_path, photo);
  env->ReleaseStringUTFChars(photo_override, photo_ovr);
  env->ReleaseStringUTFChars(overlay_path, overlay_c);
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

// Combined render/export entry points. The renderer hands back its RGBA raster
// directly, so JPEG/WebP export never creates, reads or decodes a PNG.
extern "C" JNIEXPORT jstring JNICALL
Java_com_framemark_native_engine_NativeEngineProbe_nativeRenderExportDesign(
    JNIEnv *env, jclass, jstring style_path, jstring photo_path, jstring photo_override,
    jstring exif_path, jstring assets_json, jstring fonts_dir, jstring brands_dir,
    jstring fallback_dir, jstring theme, jstring ratio, jstring design_json,
    jstring overlay_path, jfloat overlay_x, jfloat overlay_y, jfloat overlay_w,
    jfloat overlay_h, jfloat overlay_opacity, jfloat overlay_angle, jstring geom_path,
    jint max_edge, jstring icc_path, jstring exif_src, jstring out_path,
    jstring report_path, jint format, jint lossless, jfloat quality,
    jint remove_gps, jint keep_thumb) {
#if !defined(FM_ENGINE_LINKED) || !defined(FM_EXPORT_LINKED)
  (void)style_path; (void)photo_path; (void)photo_override; (void)exif_path;
  (void)assets_json; (void)fonts_dir; (void)brands_dir; (void)fallback_dir;
  (void)theme; (void)ratio; (void)design_json; (void)overlay_path;
  (void)overlay_x; (void)overlay_y; (void)overlay_w; (void)overlay_h;
  (void)overlay_opacity; (void)overlay_angle; (void)geom_path; (void)max_edge;
  (void)icc_path; (void)exif_src; (void)out_path; (void)report_path;
  (void)format; (void)lossless; (void)quality; (void)remove_gps; (void)keep_thumb;
  return ErrorEnvelope(env, "IR render/export is not linked for this ABI");
#else
  JStringUtf style(env, style_path), photo(env, photo_path), photo_ovr(env, photo_override);
  JStringUtf exif(env, exif_path), assets(env, assets_json), fonts(env, fonts_dir);
  JStringUtf brands(env, brands_dir), fallback(env, fallback_dir), theme_s(env, theme);
  JStringUtf ratio_s(env, ratio), overlay(env, overlay_path);
  JStringUtf design(env, design_json);
  JStringUtf geom(env, geom_path), icc(env, icc_path), exif_src_s(env, exif_src);
  JStringUtf out(env, out_path), report(env, report_path);
  char *render_error = nullptr;
  const auto render_started = std::chrono::steady_clock::now();
  FmIrRgbaHandle *handle = fm_render_design_rgba(
      style.get(), photo.get(), photo_ovr.get(), exif.get(), assets.get(), fonts.get(),
      brands.get(), fallback.get(), theme_s.get(), ratio_s.get(), design.get(), overlay.get(),
      overlay_x, overlay_y, overlay_w, overlay_h, overlay_opacity, overlay_angle,
      geom.get(), max_edge, &render_error);
  const unsigned long long render_ms = ElapsedMs(render_started);
  if (handle == nullptr) {
    jstring result = ErrorEnvelope(env, render_error);
    if (render_error != nullptr) fm_free_string(render_error);
    return result;
  }
  const unsigned char *rgba = fm_rgba_ir_data(handle);
  const size_t len = fm_rgba_ir_len(handle);
  const unsigned int width = fm_rgba_ir_width(handle);
  const unsigned int height = fm_rgba_ir_height(handle);
  char *json = format == 1
      ? fm_export_webp_rgba(rgba, len, width, height, icc.get(), exif_src_s.get(),
                            remove_gps, keep_thumb, lossless, quality, out.get(), report.get(), render_ms)
      : fm_export_jpeg_rgba(rgba, len, width, height, icc.get(), exif_src_s.get(),
                            remove_gps, keep_thumb, out.get(), report.get(), render_ms);
  fm_rgba_ir_free(handle);
  if (json == nullptr) return ErrorEnvelope(env, "RGBA export returned no envelope");
  jstring result = env->NewStringUTF(json);
  fm_export_free_string(json);
  return result;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_framemark_native_engine_NativeEngineProbe_nativeRenderExportHand(
    JNIEnv *env, jclass, jstring cards_path, jstring card_id, jstring hand_dir,
    jstring fonts_dir, jstring extra_fonts_dir, jstring fallback_cjk,
    jstring photo_path, jstring photo_override, jstring overlay_path,
    jfloat overlay_x, jfloat overlay_y, jfloat overlay_w, jfloat overlay_h,
    jfloat overlay_opacity, jfloat overlay_angle, jstring design_json,
    jstring geom_path, jint max_edge, jstring icc_path, jstring exif_src,
    jstring out_path, jstring report_path, jint format, jint lossless,
    jfloat quality, jint remove_gps, jint keep_thumb) {
#if !defined(FM_HAND_LINKED) || !defined(FM_EXPORT_LINKED)
  (void)cards_path; (void)card_id; (void)hand_dir; (void)fonts_dir;
  (void)extra_fonts_dir; (void)fallback_cjk; (void)photo_path; (void)photo_override;
  (void)overlay_path; (void)overlay_x; (void)overlay_y; (void)overlay_w;
  (void)overlay_h; (void)overlay_opacity; (void)overlay_angle; (void)design_json;
  (void)geom_path; (void)max_edge; (void)icc_path; (void)exif_src; (void)out_path;
  (void)report_path; (void)format; (void)lossless; (void)quality;
  (void)remove_gps; (void)keep_thumb;
  return ErrorEnvelope(env, "hand render/export is not linked for this ABI");
#else
  JStringUtf cards(env, cards_path), card(env, card_id), hand(env, hand_dir);
  JStringUtf fonts(env, fonts_dir), extra(env, extra_fonts_dir), fallback(env, fallback_cjk);
  JStringUtf photo(env, photo_path), photo_ovr(env, photo_override), overlay(env, overlay_path);
  JStringUtf design(env, design_json);
  JStringUtf geom(env, geom_path), icc(env, icc_path);
  JStringUtf exif_src_s(env, exif_src), out(env, out_path), report(env, report_path);
  char *render_error = nullptr;
  const auto render_started = std::chrono::steady_clock::now();
  FmHandRgbaHandle *handle = fm_render_hand_rgba(
      cards.get(), card.get(), hand.get(), fonts.get(), extra.get(), fallback.get(),
      photo.get(), photo_ovr.get(), overlay.get(), overlay_x, overlay_y, overlay_w,
      overlay_h, overlay_opacity, overlay_angle, design.get(), geom.get(), max_edge,
      &render_error);
  const unsigned long long render_ms = ElapsedMs(render_started);
  if (handle == nullptr) {
    jstring result = ErrorEnvelope(env, render_error);
    if (render_error != nullptr) fm_hand_free_string(render_error);
    return result;
  }
  const unsigned char *rgba = fm_rgba_hand_data(handle);
  const size_t len = fm_rgba_hand_len(handle);
  const unsigned int width = fm_rgba_hand_width(handle);
  const unsigned int height = fm_rgba_hand_height(handle);
  char *json = format == 1
      ? fm_export_webp_rgba(rgba, len, width, height, icc.get(), exif_src_s.get(),
                            remove_gps, keep_thumb, lossless, quality, out.get(), report.get(), render_ms)
      : fm_export_jpeg_rgba(rgba, len, width, height, icc.get(), exif_src_s.get(),
                            remove_gps, keep_thumb, out.get(), report.get(), render_ms);
  fm_rgba_hand_free(handle);
  if (json == nullptr) return ErrorEnvelope(env, "RGBA export returned no envelope");
  jstring result = env->NewStringUTF(json);
  fm_export_free_string(json);
  return result;
#endif
}
