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
#include <initializer_list>
#include <string>

namespace {

constexpr const char *kVersion = "framemark-engine 0.1.0-x1 (jni stub)";

// RAII for GetStringUTFChars/ReleaseStringUTFChars. A conversion can fail (the VM
// throws OutOfMemoryError and the call returns nullptr); get() then yields "" so no
// engine call ever receives a null pointer, and ok() lets a caller refuse the call
// instead of silently running it with empty paths.
class JStringUtf {
 public:
  JStringUtf(JNIEnv *env, jstring value) : env_(env), value_(value) {
    // GetStringUTFChars(nullptr) is undefined behaviour; a null jstring legitimately
    // means "no value", which reads as the empty string like before.
    if (value_ == nullptr) {
      ok_ = true;
      return;
    }
    chars_ = env_->GetStringUTFChars(value_, nullptr);
    ok_ = chars_ != nullptr;
  }
  ~JStringUtf() {
    if (chars_ != nullptr) env_->ReleaseStringUTFChars(value_, chars_);
  }
  JStringUtf(const JStringUtf &) = delete;
  JStringUtf &operator=(const JStringUtf &) = delete;
  const char *get() const { return chars_ == nullptr ? "" : chars_; }

  /** False when the UTF conversion failed (OOM / pending exception). */
  bool ok() const { return ok_; }

 private:
  JNIEnv *env_;
  jstring value_;
  const char *chars_ = nullptr;
  bool ok_ = false;
};

/** True only when every conversion succeeded. */
bool StringsOk(std::initializer_list<bool> conversions) {
  for (bool ok : conversions) {
    if (!ok) return false;
  }
  return true;
}

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

/**
 * Reports a failed string conversion through the same JSON envelope the engine uses
 * and leaves a clean VM: the pending OutOfMemoryError cannot be turned into a Java
 * string, so it is cleared before the error envelope is allocated.
 */
jstring StringConversionFailure(JNIEnv *env) {
  if (env->ExceptionCheck()) env->ExceptionClear();
  return ErrorEnvelope(env, "engine call failed: JNI string conversion failed");
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
  JStringUtf style(env, style_path), photo(env, photo_path), photo_ovr(env, photo_override);
  JStringUtf exif(env, exif_path), assets(env, assets_json), fonts(env, fonts_dir);
  JStringUtf brands(env, brands_dir), fallback(env, fallback_dir), theme_s(env, theme);
  JStringUtf ratio_s(env, ratio), design(env, design_json), overlay(env, overlay_path);
  JStringUtf out(env, out_png);
  if (!StringsOk({style.ok(), photo.ok(), photo_ovr.ok(), exif.ok(), assets.ok(), fonts.ok(),
                  brands.ok(), fallback.ok(), theme_s.ok(), ratio_s.ok(), design.ok(),
                  overlay.ok(), out.ok()})) {
    return StringConversionFailure(env);
  }
  char *err = fm_render_design(style.get(), photo.get(), photo_ovr.get(), exif.get(),
                               assets.get(), fonts.get(), brands.get(), fallback.get(),
                               theme_s.get(), ratio_s.get(), design.get(), overlay.get(),
                               overlay_x, overlay_y, overlay_w, overlay_h, overlay_opacity,
                               overlay_angle, out.get(), max_edge);
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
  JStringUtf cards(env, cards_path), card(env, card_id), hand(env, hand_dir);
  JStringUtf fonts(env, fonts_dir), extra(env, extra_fonts_dir), fallback(env, fallback_cjk);
  JStringUtf photo(env, photo_path), photo_ovr(env, photo_override), overlay(env, overlay_path);
  JStringUtf design(env, design_json), out(env, out_png);
  if (!StringsOk({cards.ok(), card.ok(), hand.ok(), fonts.ok(), extra.ok(), fallback.ok(),
                  photo.ok(), photo_ovr.ok(), overlay.ok(), design.ok(), out.ok()})) {
    return StringConversionFailure(env);
  }
  char *err = fm_render_hand(cards.get(), card.get(), hand.get(), fonts.get(), extra.get(),
                             fallback.get(), photo.get(), photo_ovr.get(), overlay.get(),
                             overlay_x, overlay_y, overlay_w, overlay_h, overlay_opacity,
                             overlay_angle, design.get(), out.get(), max_edge);
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
  JStringUtf tiff(env, tiff_path), out(env, out_png);
  if (!StringsOk({tiff.ok(), out.ok()})) return StringConversionFailure(env);
  char *err = fm_tiff_to_png(tiff.get(), out.get());
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
  JStringUtf png(env, png_path), icc(env, icc_path), exif(env, exif_src);
  JStringUtf out(env, out_webp), report(env, report_json);
  if (!StringsOk({png.ok(), icc.ok(), exif.ok(), out.ok(), report.ok()})) {
    return StringConversionFailure(env);
  }
  char *json = fm_export_webp(png.get(), icc.get(), exif.get(), remove_gps, keep_thumb,
                              lossless, quality, out.get(), report.get());
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
  JStringUtf png(env, png_path), icc(env, icc_path), exif(env, exif_src);
  JStringUtf out(env, out_jpeg), report(env, report_json);
  if (!StringsOk({png.ok(), icc.ok(), exif.ok(), out.ok(), report.ok()})) {
    return StringConversionFailure(env);
  }
  char *json = fm_export_png(png.get(), icc.get(), exif.get(), remove_gps, keep_thumb,
                             out.get(), report.get());
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
  if (!StringsOk({style.ok(), photo.ok(), photo_ovr.ok(), exif.ok(), assets.ok(), fonts.ok(),
                  brands.ok(), fallback.ok(), theme_s.ok(), ratio_s.ok(), overlay.ok(),
                  design.ok(), geom.ok(), icc.ok(), exif_src_s.ok(), out.ok(), report.ok()})) {
    return StringConversionFailure(env);
  }
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
  if (!StringsOk({cards.ok(), card.ok(), hand.ok(), fonts.ok(), extra.ok(), fallback.ok(),
                  photo.ok(), photo_ovr.ok(), overlay.ok(), design.ok(), geom.ok(),
                  icc.ok(), exif_src_s.ok(), out.ok(), report.ok()})) {
    return StringConversionFailure(env);
  }
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
